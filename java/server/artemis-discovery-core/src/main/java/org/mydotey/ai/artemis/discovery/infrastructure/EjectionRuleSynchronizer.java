package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.EjectionFilter;
import org.mydotey.ai.artemis.discovery.domain.EjectionRule;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleListener;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleSource;
import org.mydotey.ai.artemis.discovery.domain.EjectionRules;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceChange;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 摘除规则同步引擎（D-E3/D-E6/D-E7）：单同步线程循环 + per-round 有界事件队列
 * （模式同 {@link ProjectionSynchronizer} 简化——快照在流内首条，无 bootstrap 两段序）。
 *
 * <p>事件应用：快照 = 全量替换、增量 = with/without；应用 = 先 {@link EjectionFilter#replace}
 * （volatile 发布，查询/对账/翻译即时生效）后新旧规则集对称差扇出（D-E6）——受影响服务
 * 逐 cluster 取投影实例完整数据（投影无此实例则该 cluster 无推送——视图本无效果），
 * ejected-now → DELETE、recovered-now → NEW，经 {@link SubscriptionManager#onViewChanges}
 * 视图语义直投（表锁内，与投影扇出/基线互斥）。规则跨 cluster 生效（D-E5）。
 *
 * <p>fail-open（D-E3）：断流不清缓存——规则集只在收到事件时替换，断连期间最后已知
 * 规则继续生效；全抖动指数退避（默认 1s→10s）重连，恢复即重新全量覆盖收敛。
 * readiness 不受影响（规则是过滤增强，非数据面同步）。
 */
@Slf4j
public final class EjectionRuleSynchronizer implements AutoCloseable {

    static final String KEY_QUEUE_CAPACITY = "artemis.discovery.management.queueCapacity";
    static final String KEY_BACKOFF_MIN_MS = "artemis.discovery.management.reconnectBackoffMinMs";
    static final String KEY_BACKOFF_MAX_MS = "artemis.discovery.management.reconnectBackoffMaxMs";

    private static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    private static final long DEFAULT_BACKOFF_MIN_MS = 1_000;
    private static final long DEFAULT_BACKOFF_MAX_MS = 10_000;

    private final EjectionRuleSource source;
    private final EjectionFilter filter;
    private final ProjectionRegistry registry;
    private final SubscriptionManager subscriptionManager;

    private final int queueCapacity;
    private final long backoffMinMs;
    private final long backoffMaxMs;

    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean enabled = true;

    public EjectionRuleSynchronizer(
            StringProperties properties,
            EjectionRuleSource source,
            EjectionFilter filter,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager) {
        this.source = source;
        this.filter = filter;
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.queueCapacity = ScfProperties.staticInt(
                        properties,
                        KEY_QUEUE_CAPACITY,
                        DEFAULT_QUEUE_CAPACITY,
                        16,
                        1_048_576,
                        "ejection rule event queue capacity (static)")
                .getValue();
        this.backoffMinMs = staticLong(properties, KEY_BACKOFF_MIN_MS, DEFAULT_BACKOFF_MIN_MS)
                .getValue();
        this.backoffMaxMs = staticLong(properties, KEY_BACKOFF_MAX_MS, DEFAULT_BACKOFF_MAX_MS)
                .getValue();
        this.worker =
                Thread.ofPlatform().name("artemis-discovery-ejection-sync").unstarted(this::run);
    }

    /** 未配置 management（0..1，D-E4）时装配层调用：start 不启动线程、零开销。 */
    public void disable() {
        enabled = false;
    }

    public void start() {
        if (!enabled) {
            return;
        }
        worker.start();
    }

    private void run() {
        long backoffBase = 0;
        while (running) {
            try {
                syncRound();
                backoffBase = 0; // 成功一轮即重置退避
            } catch (InterruptedException e) {
                return; // close 中断
            } catch (Exception e) {
                log.warn("ejection rule sync round failed", e);
            }
            backoffBase = nextBackoffBase(backoffBase);
            try {
                sleepJittered(backoffBase);
            } catch (InterruptedException e) {
                return; // close 中断
            }
        }
    }

    /** 单轮：watch → 消费事件直到断流。断流不清缓存（D-E3），退避后整轮重来（重新全量覆盖）。 */
    private void syncRound() throws InterruptedException {
        RoundEvents events = new RoundEvents();
        try (var ignored = source.watch(new ForwardingListener(events))) {
            drain(events);
        }
    }

    private void drain(RoundEvents events) throws InterruptedException {
        while (running && !events.reconnectRequested) {
            Object event = events.queue.take();
            if (event == events.reconnectSentinel) {
                return;
            }
            apply(event);
        }
    }

    private void apply(Object event) {
        if (event instanceof SnapshotEvent snapshot) {
            EjectionRules oldRules = filter.rules();
            EjectionRules newRules = EjectionRules.of(snapshot.rules());
            applyAndFanout(oldRules, newRules);
            log.info(
                    "ejection rules snapshot applied, rules={}",
                    snapshot.rules().size());
        } else if (event instanceof EjectedEvent ejected) {
            EjectionRules oldRules = filter.rules();
            applyAndFanout(oldRules, oldRules.with(ejected.rule()));
        } else if (event instanceof RecoveredEvent recovered) {
            EjectionRules oldRules = filter.rules();
            applyAndFanout(oldRules, oldRules.without(recovered.service, recovered.instanceId));
        }
    }

    private void applyAndFanout(EjectionRules oldRules, EjectionRules newRules) {
        if (oldRules == newRules) {
            return; // 幂等增量无变化
        }
        // 表锁内「规则替换 + 扇出」成对（review FIX-1 同型）：与投影线程的「translate + apply」
        // 互斥——否则 [replace, lock] 窗口内投影 DELETE 已摘除实例会双路径皆无事件
        // （translate 判不可见、fanoutDiff 判投影已无），视图退出 DELETE 丢失。
        subscriptionManager.runWithFanout(() -> {
            filter.replace(newRules);
            fanoutDiff(oldRules, newRules);
        });
    }

    /** 新旧规则集对称差 → 受影响服务逐 cluster 视图变更直投（D-E6，跨 cluster 生效 D-E5）；
     * 调用方已持表锁。 */
    private void fanoutDiff(EjectionRules oldRules, EjectionRules newRules) {
        Set<ServiceKey> affected = new HashSet<>(oldRules.services());
        affected.addAll(newRules.services());
        for (ServiceKey service : affected) {
            Set<String> oldIds = oldRules.instances(service);
            Set<String> newIds = newRules.instances(service);
            for (String cluster : registry.clusterIds()) {
                ServiceInstances projection = registry.service(cluster, service);
                if (projection == null) {
                    continue;
                }
                List<InstanceChange> changes = null;
                for (String ejectedNow : newIds) {
                    if (!oldIds.contains(ejectedNow)) {
                        changes = addChange(changes, find(projection, ejectedNow), ChangeType.DELETE);
                    }
                }
                for (String recoveredNow : oldIds) {
                    if (!newIds.contains(recoveredNow)) {
                        changes = addChange(changes, find(projection, recoveredNow), ChangeType.NEW);
                    }
                }
                if (changes != null && !changes.isEmpty()) {
                    subscriptionManager.onViewChanges(cluster, List.of(new ServiceChange(service, changes)));
                }
            }
        }
    }

    /** 投影无此实例（instance == null）跳过该条——视图本无效果（D-E6）。 */
    private static List<InstanceChange> addChange(List<InstanceChange> changes, Instance instance, ChangeType type) {
        if (instance == null) {
            return changes;
        }
        if (changes == null) {
            changes = new ArrayList<>();
        }
        changes.add(new InstanceChange(instance, type));
        return changes;
    }

    private static Instance find(ServiceInstances projection, String instanceId) {
        for (Instance instance : projection.instances()) {
            if (instance.instanceId().equals(instanceId)) {
                return instance;
            }
        }
        return null;
    }

    /** 全抖动指数退避（同 ProjectionSynchronizer 策略）：base 倍增封顶，sleep 取 [0, base) 均匀。 */
    private long nextBackoffBase(long currentBase) {
        long next = currentBase == 0 ? backoffMinMs : Math.min(currentBase * 2, backoffMaxMs);
        return Math.max(1, next);
    }

    private void sleepJittered(long base) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextLong(base) + 1);
    }

    private static Property<String, Long> staticLong(StringProperties properties, String key, long defaultValue) {
        return ScfProperties.staticLong(properties, key, defaultValue, 100, 600_000, key + " (static)");
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 本轮事件通道：队列 + 断流标志 + 哨兵（旧轮对象被新轮替换即整体废弃）。 */
    private final class RoundEvents {

        private final Object reconnectSentinel = new Object();
        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(queueCapacity);
        private volatile boolean reconnectRequested;

        void signalReconnect() {
            reconnectRequested = true;
            queue.offer(reconnectSentinel); // 队列满时失败无妨：drain 每轮检查标志兜底
        }
    }

    /** gRPC 回调线程转发：只 offer 到本轮队列，慢消费（队列满）触发断流重连。 */
    private final class ForwardingListener implements EjectionRuleListener {

        private final RoundEvents events;

        ForwardingListener(RoundEvents events) {
            this.events = events;
        }

        @Override
        public void onSnapshot(List<EjectionRule> rules) {
            if (!events.queue.offer(new SnapshotEvent(rules))) {
                log.warn("ejection rule event queue full, reconnecting for full snapshot");
                events.signalReconnect();
            }
        }

        @Override
        public void onEjected(EjectionRule rule) {
            if (!events.queue.offer(new EjectedEvent(rule))) {
                events.signalReconnect();
            }
        }

        @Override
        public void onRecovered(ServiceKey service, String instanceId) {
            if (!events.queue.offer(new RecoveredEvent(service, instanceId))) {
                events.signalReconnect();
            }
        }

        @Override
        public void onClosed() {
            events.signalReconnect();
        }
    }

    private record SnapshotEvent(List<EjectionRule> rules) {}

    private record EjectedEvent(EjectionRule rule) {}

    private record RecoveredEvent(ServiceKey service, String instanceId) {}
}
