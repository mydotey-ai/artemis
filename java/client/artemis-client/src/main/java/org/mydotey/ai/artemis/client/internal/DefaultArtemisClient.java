package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK 默认实现（internal，经 {@code ArtemisClients.newClient(...)} 构造，勿直接使用）：
 * fire-and-forget 操作——本地集更新同步必成，上报异步（单线程有界 ops executor）；瞬态传输失败按配置
 * 有界重试（{@code artemis.client.operation.retryTimes/retryIntervalMs}），重试耗尽或终态拒绝：
 * WARN + metrics 计数 + 即时心跳补投（register/update）/ TTL 收敛（deregister），不向用户传导成败。
 * 重试前**重读本地集当前状态**：期间被注销则放弃、被更新则上报新值（防陈旧重放）。
 * close 顺序固定：停心跳 → 停上报队列并等待在途任务（有界）→ 全量注销 → 关传输。
 */
public final class DefaultArtemisClient implements ArtemisClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultArtemisClient.class);

    static final String KEY_RETRY_TIMES = "artemis.client.operation.retryTimes";
    static final String KEY_RETRY_INTERVAL_MS = "artemis.client.operation.retryIntervalMs";

    private static final int DEFAULT_RETRY_TIMES = 2;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 500;
    private static final long CLOSE_AWAIT_MS = 5_000;
    private static final int MAX_PENDING_TASKS = 1_000;

    private static final AtomicLong CLIENT_IDS = new AtomicLong();

    private enum State {
        NEW,
        RUNNING,
        CLOSED
    }

    private final LocalInstanceStore store = new LocalInstanceStore();
    private final GrpcRegistryClient grpcClient;
    private final HeartbeatChannel heartbeat;
    private final MeterRegistry registry;
    private final String clientId;
    private final Gauge instancesGauge;
    private final ConcurrentHashMap<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> droppedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> reportTimers = new ConcurrentHashMap<>();
    private final BoundedScheduledExecutor ops;
    /** 重试配置定义在本类（消费方），动态语义：使用处每次取最新值。 */
    private final Property<String, Integer> retryTimes;

    private final Property<String, Long> retryIntervalMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    public DefaultArtemisClient(StringProperties properties, MeterRegistry meterRegistry) {
        this.registry = meterRegistry;
        this.clientId = "client-" + CLIENT_IDS.incrementAndGet();
        this.retryTimes =
                properties.getIntProperty(KEY_RETRY_TIMES, new RangeValueConfig<>(DEFAULT_RETRY_TIMES, 0, 10));
        this.retryIntervalMs = properties.getLongProperty(
                KEY_RETRY_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_RETRY_INTERVAL_MS, 100L, 10_000L));
        this.grpcClient = new GrpcRegistryClient(properties);
        this.heartbeat = new HeartbeatChannel(
                grpcClient,
                new HeartbeatChannel.Supplier() {
                    @Override
                    public java.util.Map<org.mydotey.ai.artemis.client.ServiceId, List<ServiceInstance>> get() {
                        return store.snapshot();
                    }
                },
                properties,
                this.registry,
                clientId);
        // client tag 区分多 client（Micrometer 同名同 tag 首注册胜出）；close 时移除
        this.instancesGauge = Gauge.builder("artemis.client.instances", store, LocalInstanceStore::instanceCount)
                .tag("client", clientId)
                .register(registry);
        this.ops = new BoundedScheduledExecutor("ops", 1, MAX_PENDING_TASKS, new NamedOpsThreadFactory());
    }

    @Override
    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("client already started or closed");
        }
        LOG.info("artemis client started ({})", clientId);
        heartbeat.start();
    }

    @Override
    public void register(ServiceInstance instance) {
        requireRunning("register");
        store.put(instance);
        scheduleReport("register", instance, 0);
    }

    @Override
    public void update(ServiceInstance instance) {
        requireRunning("update");
        store.put(instance);
        scheduleReport("update", instance, 0);
    }

    @Override
    public void deregister(InstanceId instance) {
        requireRunning("deregister");
        store.remove(instance.getService(), instance.getInstanceId());
        List<InstanceId> batch = new ArrayList<>();
        batch.add(instance);
        scheduleDeregister(batch, 0);
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSED);
        if (previous == State.CLOSED) {
            return; // 幂等
        }
        // 屏障顺序：停心跳 → 等在途上报结束（register 完成后才发注销，防迟到的 register 复活实例）
        heartbeat.close();
        ops.shutdown();
        try {
            if (!ops.awaitTermination(CLOSE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                ops.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ops.shutdownNow();
        }
        if (previous == State.RUNNING) {
            deregisterAllBestEffort();
        }
        grpcClient.close();
        registry.remove(instancesGauge);
        LOG.info("artemis client closed ({})", clientId);
    }

    /** 上报编排：首试立即，重试按间隔调度（不占线程）；瞬态失败重试，耗尽/终态 → 兜底链。 */
    private void scheduleReport(final String operation, final ServiceInstance instance, final int attempt) {
        try {
            ops.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            // 重读本地集：期间被注销 → 放弃陈旧重放；被更新 → 上报最新值
                            ServiceInstance current = store.get(instance.getService(), instance.getInstanceId());
                            if (current == null) {
                                return;
                            }
                            long startNanos = System.nanoTime();
                            GrpcRegistryClient.ReportOutcome outcome = "register".equals(operation)
                                    ? grpcClient.reportRegister(current.getService(), current)
                                    : grpcClient.reportUpdate(current.getService(), current);
                            reportTimers
                                    .computeIfAbsent(
                                            operation,
                                            op -> Timer.builder("artemis.client.report.duration")
                                                    .tag("operation", op)
                                                    .tag("client", clientId)
                                                    .register(registry))
                                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
                            handleOutcome(operation, current.toString(), outcome, attempt, new Retry() {
                                @Override
                                public void retry() {
                                    scheduleReport(operation, instance, attempt + 1);
                                }
                            });
                        }
                    },
                    attempt == 0 ? 0 : retryIntervalMs.getValue(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            onRejected(operation, "report");
        }
    }

    private void scheduleDeregister(final List<InstanceId> instances, final int attempt) {
        try {
            ops.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            for (InstanceId instance : instances) {
                                // 期间被重新注册 → 放弃注销重试（防误删新实例）
                                if (store.get(instance.getService(), instance.getInstanceId()) != null) {
                                    return;
                                }
                            }
                            handleOutcome(
                                    "deregister",
                                    instances.toString(),
                                    grpcClient.reportDeregister(instances),
                                    attempt,
                                    new Retry() {
                                        @Override
                                        public void retry() {
                                            scheduleDeregister(instances, attempt + 1);
                                        }
                                    });
                        }
                    },
                    attempt == 0 ? 0 : retryIntervalMs.getValue(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            onRejected("deregister", "deregister");
        }
    }

    private void onRejected(String operation, String what) {
        if (state.get() == State.CLOSED) {
            return; // closing：本地集变更由 TTL/心跳收敛
        }
        LOG.warn("{} {} rejected: ops queue bounded (max {})", operation, what, MAX_PENDING_TASKS);
        droppedCounters
                .computeIfAbsent(
                        operation,
                        op -> Counter.builder("artemis.client.operation.dropped")
                                .tag("operation", op)
                                .tag("client", clientId)
                                .register(registry))
                .increment();
    }

    private void handleOutcome(
            String operation, String target, GrpcRegistryClient.ReportOutcome outcome, int attempt, Retry retry) {
        if (outcome == GrpcRegistryClient.ReportOutcome.REPORTED) {
            return;
        }
        if (outcome == GrpcRegistryClient.ReportOutcome.RETRIABLE && attempt < retryTimes.getValue()) {
            LOG.debug(
                    "retrying {} report for {} (attempt {}/{})", operation, target, attempt + 1, retryTimes.getValue());
            retry.retry();
            return;
        }
        // 重试耗尽或终态拒绝：结论性 WARN + metrics + 兜底链（register/update 即时心跳补投 / deregister TTL 收敛）
        LOG.warn(
                "{} report failed for {} after {} attempt(s), falling back to self-healing",
                operation,
                target,
                attempt + 1);
        failureCounters
                .computeIfAbsent(
                        operation,
                        op -> Counter.builder("artemis.client.operation.failure")
                                .tag("operation", op)
                                .tag("client", clientId)
                                .register(registry))
                .increment();
        if (!"deregister".equals(operation)) {
            heartbeat.sendNow();
        }
    }

    private interface Retry {
        void retry();
    }

    private void deregisterAllBestEffort() {
        List<InstanceId> instances = new ArrayList<>(store.instanceIds());
        if (instances.isEmpty()) {
            return;
        }
        if (grpcClient.reportDeregister(instances) != GrpcRegistryClient.ReportOutcome.REPORTED) {
            LOG.warn("graceful deregister failed on close, lease TTL will converge");
        }
    }

    private void requireRunning(String operation) {
        State current = state.get();
        if (current == State.NEW) {
            throw new IllegalStateException("client not started; call start() first (" + operation + ")");
        }
        if (current == State.CLOSED) {
            throw new IllegalStateException("client closed (" + operation + ")");
        }
    }

    private static final class NamedOpsThreadFactory implements ThreadFactory {
        private int count;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "artemis-client-ops-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    }
}
