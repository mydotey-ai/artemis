package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceChange;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

/**
 * 管理规则流同步骨架（phase-3 #9 收口，ejection/route-rule 两同步器同构抽象）：
 * 单同步线程循环 + per-round 有界事件队列 + 全抖动指数退避重连。fail-open（D-E3）：
 * 断流不清缓存（子类规则集只在收到事件时替换），断连期间最后已知规则继续生效；
 * readiness 不受影响（规则是过滤增强，非数据面同步）。
 *
 * <p>状态转移：每轮起点 SYNCING、首次成功 apply 起 SYNCED、同步过一轮后断流
 * DISCONNECTED（规则冻结）；ack 失败 WARN 放弃——重连快照后重新 ack 收敛。
 */
@Slf4j
abstract class ManagementRuleSynchronizer implements AutoCloseable {

    /** 本轮事件出口：offer false = 慢消费（队列满），转发方应 signalReconnect 断流重连。 */
    protected interface EventSink {

        boolean offer(Object event);

        void signalReconnect();
    }

    protected final ProjectionRegistry registry;

    protected final SubscriptionManager subscriptionManager;

    private final ManagementStatus managementStatus;

    private final int queueCapacity;
    private final long backoffMinMs;
    private final long backoffMaxMs;

    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean enabled = true;
    private boolean everApplied; // worker 线程独占（断连判定）

    protected ManagementRuleSynchronizer(
            ManagementSyncProperties sync,
            ManagementStatus managementStatus,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            String workerName) {
        this.managementStatus = managementStatus;
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.queueCapacity = sync.queueCapacity;
        this.backoffMinMs = sync.backoffMinMs;
        this.backoffMaxMs = sync.backoffMaxMs;
        this.worker = Thread.ofPlatform().name(workerName).unstarted(this::run);
    }

    /** 打开 watch 流（事件转发进 sink；返回流句柄，由骨架 try-with-resources 关闭）。 */
    protected abstract AutoCloseable watch(EventSink sink) throws Exception;

    /** 应用单事件（规则集替换/增量 + 过滤器发布 + 扇出 + applied 收尾）。 */
    protected abstract void apply(Object event);

    /** 上报已应用版本（短超时 unary；失败上抛由骨架 WARN 容忍）。 */
    protected abstract void ack(long appliedVersion);

    /** 未配置 management（0..1，D-E4）时装配层调用：start 不启动线程、零开销。 */
    public void disable() {
        enabled = false;
        managementStatus.update(ManagementState.DISABLED);
    }

    public void start() {
        if (!enabled) {
            return;
        }
        managementStatus.update(ManagementState.SYNCING);
        worker.start();
    }

    private void run() {
        long backoffBase = 0;
        while (running) {
            try {
                managementStatus.update(ManagementState.SYNCING);
                syncRound();
                backoffBase = 0; // 成功一轮即重置退避
            } catch (InterruptedException e) {
                return; // close 中断
            } catch (Exception e) {
                log.warn("{} sync round failed", worker.getName(), e);
            }
            if (everApplied) {
                managementStatus.update(ManagementState.DISCONNECTED); // 规则冻结、最后已知规则继续（D-E3）
            }
            backoffBase = nextBackoffBase(backoffBase);
            try {
                sleepJittered(backoffBase);
            } catch (InterruptedException e) {
                return; // close 中断
            }
        }
    }

    /** 单轮：watch → 消费事件直到断流。断流不清缓存（D-E3），退避后整轮重来。 */
    private void syncRound() throws InterruptedException {
        RoundEvents events = new RoundEvents();
        try (var ignored = watch(events)) {
            drain(events);
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) { // watch 打开失败 / 流句柄 close 异常
            throw new IllegalStateException("management rule watch failed", e);
        }
    }

    private void drain(RoundEvents events) throws InterruptedException {
        while (running && !events.reconnectRequested) {
            Object event = events.queue.take();
            if (event == events.reconnectSentinel) {
                return;
            }
            apply(event);
            everApplied = true;
            managementStatus.update(ManagementState.SYNCED);
        }
    }

    /** 应用收尾：推进本地已应用版本并上报 management（失败 WARN 放弃，重连快照重新 ack 收敛）。 */
    protected final void applied(long version) {
        managementStatus.updateAppliedVersion(version);
        try {
            ack(version);
        } catch (Exception e) {
            log.warn("{} ack failed, version={}", worker.getName(), version, e);
        }
    }

    /**
     * 新旧可见性对称差 → 受影响服务逐 cluster 视图变更直投（D-E6，跨 cluster 生效
     * D-E5）；调用方已持表锁（经 {@link SubscriptionManager#runWithFanout}，与投影
     * 扇出/基线互斥）。投影无此服务的 cluster 无推送（视图本无效果）。
     * ponytail: 扇出全投影扫描（低频运维操作）；规则 × 服务规模增长后按 metadata 反向索引。
     */
    protected final void fanoutVisibilityDiff(
            Set<ServiceKey> affected,
            BiPredicate<ServiceKey, Instance> wasVisible,
            BiPredicate<ServiceKey, Instance> isVisible) {
        Set<String> clusters = registry.clusterIds(); // 每扇出取一次（避免 per-service 集合拷贝）
        for (ServiceKey service : affected) {
            for (String cluster : clusters) {
                ServiceInstances projection = registry.service(cluster, service);
                if (projection == null) {
                    continue;
                }
                List<InstanceChange> changes = null;
                for (Instance instance : projection.instances()) {
                    boolean was = wasVisible.test(service, instance);
                    boolean now = isVisible.test(service, instance);
                    if (!was && now) {
                        changes = addChange(changes, instance, ChangeType.NEW);
                    } else if (was && !now) {
                        changes = addChange(changes, instance, ChangeType.DELETE);
                    }
                }
                if (changes != null && !changes.isEmpty()) {
                    subscriptionManager.onViewChanges(cluster, List.of(new ServiceChange(service, changes)));
                }
            }
        }
    }

    private static List<InstanceChange> addChange(List<InstanceChange> changes, Instance instance, ChangeType type) {
        if (changes == null) {
            changes = new ArrayList<>();
        }
        changes.add(new InstanceChange(instance, type));
        return changes;
    }

    /** 全抖动指数退避（同 ProjectionSynchronizer 策略）：base 倍增封顶，sleep 取 [0, base) 均匀。 */
    private long nextBackoffBase(long currentBase) {
        long next = currentBase == 0 ? backoffMinMs : Math.min(currentBase * 2, backoffMaxMs);
        return Math.max(1, next);
    }

    private void sleepJittered(long base) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextLong(base) + 1);
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        if (enabled) {
            managementStatus.update(ManagementState.DISCONNECTED); // 停机后不再连接规则源
        }
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 本轮事件通道：队列 + 断流标志 + 哨兵（旧轮对象被新轮替换即整体废弃）。 */
    private final class RoundEvents implements EventSink {

        private final Object reconnectSentinel = new Object();
        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(queueCapacity);
        private volatile boolean reconnectRequested;

        @Override
        public boolean offer(Object event) {
            return queue.offer(event);
        }

        @Override
        public void signalReconnect() {
            reconnectRequested = true;
            queue.offer(reconnectSentinel); // 队列满时失败无妨：drain 每轮检查标志兜底
        }
    }
}
