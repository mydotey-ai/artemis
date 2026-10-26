package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleKey;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleListener;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleSource;
import org.mydotey.ai.artemis.discovery.domain.RouteRules;

/**
 * 路由规则同步引擎（phase-2）：单同步线程循环 + per-round 有界事件队列 + 全抖动退避
 * 重连（骨架同 {@link EjectionRuleSynchronizer}，减去扇出——phase-2 只缓存规则视图，
 * phase-3 Group filter 接执行点时按 ejection {@code runWithFanout} 模式补视图直投）。
 *
 * <p>应用 = 整集替换（快照）或幂等集合操作（增量 upsert/delete），volatile 发布当前
 * 集（{@link #rules()} 本地视图 + IT 断言通道）。fail-open（D-E3）：断流不清缓存，
 * 最后已知规则继续；重连重新全量覆盖收敛。生效 ack：每次应用后向 management 上报
 * 已应用版本（worker 线程内联短超时，失败 WARN 放弃——重连快照后重新 ack 收敛）。
 */
@Slf4j
public final class RouteRuleSynchronizer implements AutoCloseable {

    private final RouteRuleSource source;
    private final ManagementStatus managementStatus;

    private final int queueCapacity;
    private final long backoffMinMs;
    private final long backoffMaxMs;

    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean enabled = true;
    private boolean everSynced; // worker 线程独占（断连判定，同 EjectionRuleSynchronizer）
    private volatile RouteRules current = RouteRules.EMPTY;

    public RouteRuleSynchronizer(
            ManagementSyncProperties sync, RouteRuleSource source, ManagementStatus managementStatus) {
        this.source = source;
        this.managementStatus = managementStatus;
        this.queueCapacity = sync.queueCapacity;
        this.backoffMinMs = sync.backoffMinMs;
        this.backoffMaxMs = sync.backoffMaxMs;
        this.worker =
                Thread.ofPlatform().name("artemis-discovery-route-rule-sync").unstarted(this::run);
    }

    /** 当前规则集（volatile 发布；本地视图与 IT 断言通道，phase-3 filter 消费）。 */
    public RouteRules rules() {
        return current;
    }

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
                log.warn("route rule sync round failed", e);
            }
            if (everSynced) {
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
            everSynced = true;
            current = RouteRules.of(snapshot.rules());
            log.info(
                    "route rules snapshot applied, rules={}, version={}",
                    snapshot.rules().size(),
                    snapshot.version());
            applied(snapshot.version());
        } else if (event instanceof UpsertedEvent upserted) {
            current = current.with(upserted.rule());
            applied(upserted.version());
        } else if (event instanceof DeletedEvent deleted) {
            current = current.without(deleted.key());
            applied(deleted.version());
        }
    }

    /** 应用收尾：推进本地已应用版本并上报 management（失败 WARN 放弃，重连快照重新 ack）。 */
    private void applied(long version) {
        managementStatus.update(ManagementState.SYNCED);
        managementStatus.updateAppliedVersion(version);
        try {
            source.ack(version);
        } catch (Exception e) {
            log.warn("route rule ack failed, version={}", version, e);
        }
    }

    /** 全抖动指数退避（同 EjectionRuleSynchronizer 策略）。 */
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
    private final class ForwardingListener implements RouteRuleListener {

        private final RoundEvents events;

        ForwardingListener(RoundEvents events) {
            this.events = events;
        }

        @Override
        public void onSnapshot(java.util.List<RouteRule> rules, long version) {
            if (!events.queue.offer(new SnapshotEvent(rules, version))) {
                log.warn("route rule event queue full, reconnecting for full snapshot");
                events.signalReconnect();
            }
        }

        @Override
        public void onUpserted(RouteRule rule, long version) {
            if (!events.queue.offer(new UpsertedEvent(rule, version))) {
                events.signalReconnect();
            }
        }

        @Override
        public void onDeleted(RouteRuleKey key, long version) {
            if (!events.queue.offer(new DeletedEvent(key, version))) {
                events.signalReconnect();
            }
        }

        @Override
        public void onClosed() {
            events.signalReconnect();
        }
    }

    private record SnapshotEvent(java.util.List<RouteRule> rules, long version) {}

    private record UpsertedEvent(RouteRule rule, long version) {}

    private record DeletedEvent(RouteRuleKey key, long version) {}
}
