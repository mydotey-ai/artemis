package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 前置寻址调度：周期（成功稳态 refreshIntervalMs）刷新节点列表；失败按指数退避 + 抖动重试
 * （成功归零回到稳态节奏）；熔断事件经 {@link #refreshNow()} 立即触发一次寻址（打断退避、
 * 不重置退避计数）。拉取失败保留旧列表（勘误 §8-5，provider 列表不被清空）。
 * 单线程 lazy executor（未 start 的 client 不占线程）；寻址失败计数入指标（outcome tag）。
 */
final class NodeListRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(NodeListRefresher.class);

    static final String KEY_REFRESH_INTERVAL_MS = "artemis.client.registry.address.refreshIntervalMs";
    static final String KEY_RETRY_BASE_MS = "artemis.client.registry.address.retryBaseMs";
    static final String KEY_RETRY_MAX_MS = "artemis.client.registry.address.retryMaxMs";

    private static final long DEFAULT_REFRESH_INTERVAL_MS = 300_000;
    private static final long DEFAULT_RETRY_BASE_MS = 2_000;
    private static final long DEFAULT_RETRY_MAX_MS = 60_000;
    private static final int MAX_PENDING_TASKS = 8;

    private final NodeListFetcher fetcher;
    private final ServerAddressProvider provider;
    private final Counter refreshSuccess;
    private final Counter refreshFailure;
    private final Counter refreshEmpty;

    /** 配置定义在本类（消费方），动态语义：使用处每次取最新值。 */
    private final Property<String, Long> refreshIntervalMs;

    private final Property<String, Long> retryBaseMs;
    private final Property<String, Long> retryMaxMs;

    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicBoolean pending = new AtomicBoolean();
    /** refreshNow 已排立即任务：运行中 refresh 的尾部不再自续（单链让位）。 */
    private volatile boolean immediateRefreshRequested;

    private volatile BoundedScheduledExecutor scheduler;
    private volatile ScheduledFuture<?> scheduled;
    private volatile boolean closed;

    NodeListRefresher(
            NodeListFetcher fetcher,
            ServerAddressProvider provider,
            StringProperties properties,
            MeterRegistry registry,
            String clientId) {
        this.fetcher = fetcher;
        this.provider = provider;
        this.refreshIntervalMs = properties.getLongProperty(
                KEY_REFRESH_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_REFRESH_INTERVAL_MS, 1000L, 3_600_000L));
        this.retryBaseMs = properties.getLongProperty(
                KEY_RETRY_BASE_MS, new RangeValueConfig<>(DEFAULT_RETRY_BASE_MS, 100L, 60_000L));
        this.retryMaxMs = properties.getLongProperty(
                KEY_RETRY_MAX_MS, new RangeValueConfig<>(DEFAULT_RETRY_MAX_MS, 1000L, 600_000L));
        this.refreshSuccess =
                registry.counter("artemis.client.nodelist.refresh", "client", clientId, "outcome", "success");
        this.refreshFailure =
                registry.counter("artemis.client.nodelist.refresh", "client", clientId, "outcome", "failure");
        this.refreshEmpty = registry.counter("artemis.client.nodelist.refresh", "client", clientId, "outcome", "empty");
    }

    /** 启动：立即寻址一次（冷启动列表就绪），随后自续周期。 */
    synchronized void start() {
        if (closed) {
            throw new IllegalStateException("node list refresher closed");
        }
        if (scheduler == null) {
            scheduler = new BoundedScheduledExecutor(
                    "registry-address", 1, MAX_PENDING_TASKS, new NamedDaemonThreadFactory("artemis-client-address"));
        }
        scheduleNext(0);
    }

    /**
     * 立即重寻址（熔断触发）：打断退避/周期中已排队任务、立即重排一次。refresh 正在执行时
     * 由其尾部让位（{@code immediateRefreshRequested}，锁内互斥）——不直接放行 pending，
     * 否则运行中 refresh 尾部 CAS 与本方法各排一个后继，周期链永久翻倍（单链不变量）。
     */
    void refreshNow() {
        if (closed) {
            return;
        }
        synchronized (this) {
            ScheduledFuture<?> queued = scheduled;
            if (queued != null) {
                queued.cancel(false);
                scheduled = null;
            }
            immediateRefreshRequested = true;
            scheduleNext(0);
        }
    }

    void close() {
        closed = true;
        BoundedScheduledExecutor current = scheduler;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void scheduleNext(long delayMs) {
        BoundedScheduledExecutor current = scheduler;
        if (current == null || closed) {
            pending.set(false);
            return;
        }
        try {
            scheduled = current.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.set(false);
        }
    }

    private void refresh() {
        pending.set(false);
        long nextDelay;
        try {
            List<NodeEndpoint> fetched = fetcher.fetch();
            if (fetched.isEmpty()) {
                // 空 members（服务端无集群配置形态）与拉取失败区分观测，均按退避重试
                refreshEmpty.increment();
                LOG.debug("node list empty (server without members?), retrying with backoff");
                nextDelay = backoffDelayMs(attempt.incrementAndGet());
            } else {
                provider.updateNodes(fetched);
                attempt.set(0);
                refreshSuccess.increment();
                nextDelay = refreshIntervalMs.getValue();
            }
        } catch (Exception e) {
            int currentAttempt = attempt.incrementAndGet();
            refreshFailure.increment();
            LOG.debug("node list refresh failed (attempt {}): {}", currentAttempt, e.getMessage());
            if (currentAttempt == 1) {
                LOG.warn("node list refresh failed, keeping last known list and retrying with backoff");
            }
            nextDelay = backoffDelayMs(currentAttempt);
        }
        if (!closed) {
            synchronized (this) {
                boolean yieldToImmediate = immediateRefreshRequested; // refreshNow 已排立即任务：本链让位
                immediateRefreshRequested = false;
                if (!yieldToImmediate && pending.compareAndSet(false, true)) {
                    scheduleNext(nextDelay);
                }
            }
        }
    }

    /** 指数退避 base*2^(attempt-1)（上限 max）× [0.8, 1.2) 抖动（对齐心跳重连退避模式）。 */
    private long backoffDelayMs(int attempt) {
        long base = retryBaseMs.getValue();
        long max = retryMaxMs.getValue();
        long exponential = base;
        for (int i = 1; i < attempt && exponential < max; i++) {
            exponential = Math.min(exponential * 2, max);
        }
        double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
        long delay = (long) (Math.min(exponential, max) * jitter);
        return Math.max(Math.min(delay, max), 1);
    }
}
