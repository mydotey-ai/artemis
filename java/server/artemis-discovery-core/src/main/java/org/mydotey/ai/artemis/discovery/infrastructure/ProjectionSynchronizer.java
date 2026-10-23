package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.discovery.domain.ProjectionDiff;
import org.mydotey.ai.artemis.discovery.domain.ProjectionListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSession;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSource;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSyncListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamState;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 上游投影同步引擎（D-D3/D-D4）：单同步线程循环 + 每轮独立有界事件队列。
 *
 * <p>消费安全序（D-D3）：Watch 建立确认 → Bootstrap（blocking）→ <b>立即整表覆盖</b> →
 * drain 事件队列。gRPC 回调线程只 offer（非阻塞）；覆盖先于一切事件应用——覆盖前积压的
 * 事件中，产生于快照读取前者已被快照吸收（幂等覆盖），产生于后者者必随后到达（服务端按
 * 产生序推送），两者都在覆盖后按到达序应用，无丢失。
 *
 * <p>轮次隔离：事件队列与断流标志均为 per-round（随 {@link RoundEvents} 创建），旧轮
 * watch 在 close 竞态窗口内的迟到回调只落入旧轮对象，不污染新轮——旧轮 flag 置位后无人
 * 消费，旧队列随引用释放。
 *
 * <p>自愈（D-D4）：断流/失败/队列满（慢消费）→ 关会话 → 全抖动指数退避（1s→10s）→
 * 整轮重来（watch 与 bootstrap 同会话同端点，重开换端点）。readiness 只升不降（D-D5）：
 * 首轮 SYNCED 通知 {@link ProjectionSyncListener#onSynced()} 一次；断连通知
 * onDisconnected 仅在曾同步成功后发生（从未同步成功保持 SYNCING，不虚报陈旧数据）。
 */
@Slf4j
public final class ProjectionSynchronizer implements AutoCloseable {

    static final String KEY_QUEUE_CAPACITY = "artemis.discovery.projection.queueCapacity";
    static final String KEY_ESTABLISH_TIMEOUT_MS = "artemis.discovery.upstream.establishTimeoutMs";
    static final String KEY_BACKOFF_MIN_MS = "artemis.discovery.upstream.reconnectBackoffMinMs";
    static final String KEY_BACKOFF_MAX_MS = "artemis.discovery.upstream.reconnectBackoffMaxMs";

    private static final int DEFAULT_QUEUE_CAPACITY = 8192;
    private static final long DEFAULT_ESTABLISH_TIMEOUT_MS = 10_000;
    private static final long DEFAULT_BACKOFF_MIN_MS = 1_000;
    private static final long DEFAULT_BACKOFF_MAX_MS = 10_000;

    private final ProjectionSource source;
    private final ProjectionRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final String clusterId;
    private final ProjectionSyncListener syncListener;

    private final int queueCapacity;
    private final long establishTimeoutMs;
    private final long backoffMinMs;
    private final long backoffMaxMs;

    private final Thread worker;
    private volatile boolean running = true;
    private volatile UpstreamState state = UpstreamState.SYNCING;
    private boolean everSynced; // worker 线程独占（首轮 SYNCED 判定，readiness 只升不降）

    public ProjectionSynchronizer(
            StringProperties properties,
            ProjectionSource source,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            String clusterId,
            ProjectionSyncListener syncListener) {
        this.source = source;
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.clusterId = clusterId;
        this.syncListener = syncListener;
        this.queueCapacity = ScfProperties.staticInt(
                        properties,
                        KEY_QUEUE_CAPACITY,
                        DEFAULT_QUEUE_CAPACITY,
                        16,
                        1_048_576,
                        "event queue capacity for upstream projection changes (static)")
                .getValue();
        this.establishTimeoutMs = staticLong(properties, KEY_ESTABLISH_TIMEOUT_MS, DEFAULT_ESTABLISH_TIMEOUT_MS)
                .getValue();
        this.backoffMinMs = staticLong(properties, KEY_BACKOFF_MIN_MS, DEFAULT_BACKOFF_MIN_MS)
                .getValue();
        this.backoffMaxMs = staticLong(properties, KEY_BACKOFF_MAX_MS, DEFAULT_BACKOFF_MAX_MS)
                .getValue();
        this.worker =
                Thread.ofPlatform().name("artemis-discovery-sync-" + clusterId).unstarted(this::run);
    }

    public UpstreamState state() {
        return state;
    }

    public void start() {
        worker.start();
    }

    private void run() {
        long backoffBase = 0;
        while (running) {
            try {
                state = UpstreamState.SYNCING;
                syncRound();
                backoffBase = 0; // 成功一轮即重置退避
            } catch (InterruptedException e) {
                return; // close 中断
            } catch (Exception e) {
                log.warn("projection sync round failed, cluster={}", clusterId, e);
            }
            if (everSynced) {
                state = UpstreamState.DISCONNECTED;
                syncListener.onDisconnected(); // 只对「曾同步成功」报断连（SYNCING 期失败保持 SYNCING）
            }
            backoffBase = nextBackoffBase(backoffBase);
            try {
                sleepJittered(backoffBase);
            } catch (InterruptedException e) {
                return; // close 中断
            }
        }
    }

    /** 单轮同步：会话（同端点）内 Watch 确认 → Bootstrap 覆盖 → drain 变更直到断流。 */
    private void syncRound() throws InterruptedException {
        CountDownLatch established = new CountDownLatch(1);
        RoundEvents events = new RoundEvents();
        try (ProjectionSession session = source.open(clusterId)) {
            ForwardingListener forwarding = new ForwardingListener(events, established);
            try (ProjectionWatch ignored = session.watch(clusterId, forwarding)) {
                if (!established.await(establishTimeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("watch establishment timeout, cluster=" + clusterId);
                }
                if (forwarding.failed) {
                    // 流在建立确认前终止：立即作废本轮重开，不等超时（重连窗口收敛）
                    throw new IllegalStateException("watch closed before establishment, cluster=" + clusterId);
                }
                List<ServiceInstances> snapshot = session.bootstrap();
                resyncWithDiff(snapshot); // diff 续推先于覆盖（D-S7：订阅流与上游状态解耦）
                state = UpstreamState.SYNCED;
                if (!everSynced) {
                    everSynced = true;
                    syncListener.onSynced(); // 只在首轮触发（readiness 只升不降，D-D5）
                }
                log.info("projection synced, cluster={}, services={}", clusterId, snapshot.size());
                drainChanges(events);
            }
        }
    }

    /** 覆盖后消费本轮变更队列直到断流信号（RECONNECT 哨兵 / 断流标志 / close）。 */
    private void drainChanges(RoundEvents events) throws InterruptedException {
        while (running && !events.reconnectRequested) {
            Object event = events.queue.take();
            if (event == events.reconnectSentinel) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<ServiceChange> batch = (List<ServiceChange>) event;
            // 扇出先于应用（D-S4）：labels 翻译需本批应用前旧值；表锁内成对执行，
            // 批中间态（已推送未应用）对订阅基线不可见（FIX-1）
            subscriptionManager.runWithFanout(() -> {
                subscriptionManager.onChanges(clusterId, batch, key -> registry.service(clusterId, key));
                registry.apply(clusterId, batch);
            });
        }
    }

    /**
     * 重连覆盖（D-S7）：旧投影 T0 与新快照 T1 diff 续推（与稳态增量同一扇出路径，旧值
     * 取 T0）后再整表覆盖——上游重连对订阅方透明，registry 全冷重启时 diff = 全 DELETE。
     */
    private void resyncWithDiff(List<ServiceInstances> snapshot) {
        Map<ServiceKey, ServiceInstances> oldByKey = new HashMap<>();
        for (ServiceInstances service : registry.services(clusterId)) {
            oldByKey.put(service.key(), service);
        }
        subscriptionManager.runWithFanout(() -> {
            subscriptionManager.onChanges(clusterId, ProjectionDiff.diff(oldByKey, snapshot), oldByKey::get);
            registry.replaceAll(clusterId, snapshot); // 覆盖先于 drain（D-D3）
        });
    }

    /** 全抖动指数退避（同 registry peer bootstrap 策略）：base 倍增封顶，sleep 取 [0, base) 均匀。 */
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
    private final class ForwardingListener implements ProjectionListener {

        private final RoundEvents events;
        private final CountDownLatch established;
        private volatile boolean failed;

        ForwardingListener(RoundEvents events, CountDownLatch established) {
            this.events = events;
            this.established = established;
        }

        @Override
        public void onEstablished() {
            established.countDown();
        }

        @Override
        public void onChanges(List<ServiceChange> batch) {
            if (!events.queue.offer(batch)) {
                log.warn("projection event queue full, slow consumer, reconnecting, cluster={}", clusterId);
                events.signalReconnect();
            }
        }

        @Override
        public void onClosed() {
            failed = true;
            established.countDown(); // 唤醒等待建立确认的同步线程（失败立即重来，不等超时）
            events.signalReconnect();
        }
    }
}
