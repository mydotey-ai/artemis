package org.mydotey.ai.artemis.client.internal;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Gauge;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.mydotey.ai.artemis.client.DiscoveryListener;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.DiscoveryStatus;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.Subscription;
import org.mydotey.ai.artemis.client.UpstreamState;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.ClusterChecksums;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.ServiceChecksum;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.discovery.v1.UpstreamStatus;
import org.mydotey.scf.Property;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单订阅同步引擎（internal）：订阅推送 + bootstrap/周期全量替换 + checksum 对账 + 磁盘快照。
 *
 * <p>单线程约束：所有视图写（applyEvent/replaceAll/replay）与刷新状态只在 client 的 applier 线程；
 * gRPC 回调只做映射 + enqueue（不碰视图）。刷新期间推送入有界缓冲，全量替换后按序重放，
 * 既删除断连期间消失的服务，又不制造视图空窗（陈旧换可用）。
 */
final class DiscoverySubscription implements Subscription, SnapshotSource {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoverySubscription.class);

    static final String KEY_RECONCILE_INTERVAL_MS = "artemis.client.discovery.reconcile.intervalMs";
    static final String KEY_REFRESH_INTERVAL_MS = "artemis.client.discovery.refresh.intervalMs";
    static final String KEY_RECONNECT_BASE_MS = "artemis.client.discovery.reconnect.baseDelayMs";
    static final String KEY_RECONNECT_MAX_MS = "artemis.client.discovery.reconnect.maxDelayMs";

    private static final long DEFAULT_RECONCILE_INTERVAL_MS = 60_000;
    private static final long DEFAULT_REFRESH_INTERVAL_MS = 900_000;
    private static final long DEFAULT_RECONNECT_BASE_MS = 1_000;
    private static final long DEFAULT_RECONNECT_MAX_MS = 30_000;
    private static final int MAX_EVENT_BUFFER = 8_192;
    private static final java.util.concurrent.atomic.AtomicLong SUBSCRIPTION_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long CLOSE_BARRIER_MS = 2_000;

    private static final class BufferedChange {
        final String cluster;
        final ServiceChange change;

        BufferedChange(String cluster, ServiceChange change) {
            this.cluster = cluster;
            this.change = change;
        }
    }

    private final DefaultDiscoveryClient owner;
    private final DiscoverySelector selector;
    private final DiscoveryListener listener;
    private final String subscriptionTag;
    private final LocalDiscoveryCache cache = new LocalDiscoveryCache();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();

    private final Property<String, Long> reconcileIntervalMs;
    private final Property<String, Long> refreshIntervalMs;
    private final Property<String, Long> reconnectBaseMs;
    private final Property<String, Long> reconnectMaxMs;

    private final Gauge connectedGauge;
    private final Gauge servicesGauge;

    private final Object snapshotLock = new Object();
    /** 视图变更代际与已持久化代际（避免无变更时 close 重写快照刷新时间戳）。 */
    private final java.util.concurrent.atomic.AtomicLong changeEpoch = new java.util.concurrent.atomic.AtomicLong();

    private volatile long persistedEpoch;

    private volatile boolean connected;
    private volatile boolean snapshotReplay;
    private volatile long snapshotTimestampMs;
    private volatile Map<String, UpstreamState> upstreamStates = Collections.emptyMap();
    private volatile GrpcDiscoveryClient.DiscoveryStream stream;
    private volatile java.util.concurrent.ScheduledFuture<?> reconcileFuture;
    private volatile java.util.concurrent.ScheduledFuture<?> refreshLoopFuture;
    private volatile java.util.concurrent.ScheduledFuture<?> reconnectFuture;
    private volatile java.util.concurrent.ScheduledFuture<?> refreshRetryFuture;

    // ---- applier 线程独占状态（不跨线程读写；volatile 状态只用于 status 读取） ----
    private boolean refreshing;
    private boolean refreshRequested;
    private int refreshGeneration;
    private final ArrayDeque<BufferedChange> buffer = new ArrayDeque<>();

    DiscoverySubscription(DefaultDiscoveryClient owner, DiscoverySelector selector, DiscoveryListener listener) {
        this.owner = owner;
        this.selector = selector;
        this.listener = listener;
        // 指标/日志标签：选择器指纹 + 本地序号（同选择器重复订阅也可区分，避免 Gauge id 冲突）
        this.subscriptionTag = selector.fingerprint() + "-" + SUBSCRIPTION_IDS.incrementAndGet();
        this.reconcileIntervalMs = owner.properties()
                .getLongProperty(
                        KEY_RECONCILE_INTERVAL_MS,
                        new RangeValueConfig<>(DEFAULT_RECONCILE_INTERVAL_MS, 1_000L, 3_600_000L));
        this.refreshIntervalMs = owner.properties()
                .getLongProperty(
                        KEY_REFRESH_INTERVAL_MS,
                        new RangeValueConfig<>(DEFAULT_REFRESH_INTERVAL_MS, 60_000L, 86_400_000L));
        this.reconnectBaseMs = owner.properties()
                .getLongProperty(
                        KEY_RECONNECT_BASE_MS, new RangeValueConfig<>(DEFAULT_RECONNECT_BASE_MS, 100L, 60_000L));
        this.reconnectMaxMs = owner.properties()
                .getLongProperty(
                        KEY_RECONNECT_MAX_MS, new RangeValueConfig<>(DEFAULT_RECONNECT_MAX_MS, 1_000L, 600_000L));

        // 快照回放先于建流（构造期无并发 applier 任务，直接写缓存安全）
        DiscoverySnapshotStore.Snapshot snapshot = owner.snapshotStore().load(selector, subscriptionTag);
        if (snapshot != null && !snapshot.entries.isEmpty()) {
            cache.replaceAll(snapshot.entries);
            snapshotTimestampMs = snapshot.timestampMs;
            snapshotReplay = true;
        }
        this.connectedGauge = Gauge.builder(
                        "artemis.client.discovery.connected", this, sub -> sub.connected ? 1.0 : 0.0)
                .tags("client", owner.clientId(), "subscription", subscriptionTag)
                .register(owner.registry());
        this.servicesGauge = Gauge.builder("artemis.client.discovery.services", this, sub -> sub.cache.serviceCount())
                .tags("client", owner.clientId(), "subscription", subscriptionTag)
                .register(owner.registry());

        try {
            scheduleReconcile();
            scheduleRefreshLoop();
            owner.scheduler().execute(this::connect);
        } catch (RejectedExecutionException e) {
            closed.set(true);
            cancelScheduledTasks();
            owner.registry().remove(connectedGauge);
            owner.registry().remove(servicesGauge);
            throw new IllegalStateException("discovery scheduler queue full, subscription rejected", e);
        }
    }

    @Override
    public DiscoverySelector selector() {
        return selector;
    }

    @Override
    public List<ServiceInstance> getInstances(ServiceId service) {
        requireOpen();
        return cache.getInstances(service);
    }

    @Override
    public DiscoveryStatus status() {
        requireOpen();
        return new DiscoveryStatus(connected, snapshotReplay, snapshotTimestampMs, upstreamStates);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected = false;
        GrpcDiscoveryClient.DiscoveryStream current = stream;
        if (current != null) {
            current.cancel();
        }
        cancelScheduledTasks();
        awaitApplierBarrier(); // applier 有界等待在途任务，终写看到已应用的变更
        owner.snapshotWriter().remove(this);
        if (changeEpoch.get() > persistedEpoch) {
            writeSnapshot(); // 仅视图有变更时终写（快照回放/无变更不刷新时间戳）
        }
        owner.registry().remove(connectedGauge);
        owner.registry().remove(servicesGauge);
        owner.subscriptionClosed(this);
        LOG.info("discovery subscription closed ({}), {} service(s)", selector, cache.serviceCount());
    }

    /** 取消本订阅已排期任务（防止反复建删订阅耗尽共享 scheduler 队列）。 */
    private void cancelScheduledTasks() {
        cancel(reconcileFuture);
        cancel(refreshLoopFuture);
        cancel(reconnectFuture);
        cancel(refreshRetryFuture);
    }

    private static void cancel(java.util.concurrent.ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    /** applier 屏障：保证 close 前已入队的视图写全部完成（有界等待，队列满时尽力而为）。 */
    private void awaitApplierBarrier() {
        final java.util.concurrent.CountDownLatch barrier = new java.util.concurrent.CountDownLatch(1);
        if (!owner.enqueueForClose(new Runnable() {
            @Override
            public void run() {
                barrier.countDown();
            }
        })) {
            return;
        }
        try {
            if (!barrier.await(CLOSE_BARRIER_MS, TimeUnit.MILLISECONDS)) {
                LOG.warn("discovery applier barrier timed out on close ({})", selector);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    int serviceCount() {
        return cache.serviceCount();
    }

    boolean isConnected() {
        return connected;
    }

    /** 异步 writer 落盘（合并）；close 时另行同步终写。 */
    @Override
    public void writeSnapshot() {
        synchronized (snapshotLock) {
            long epoch = changeEpoch.get();
            long timestamp = owner.snapshotStore().save(selector, cache.entries(), subscriptionTag);
            if (timestamp > 0) {
                snapshotTimestampMs = timestamp;
                if (epoch > persistedEpoch) {
                    persistedEpoch = epoch;
                }
            }
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("subscription closed");
        }
    }

    // ---- 订阅流 ----

    private void connect() {
        if (closed.get()) {
            return;
        }
        try {
            SubscribeRequest request = ProtoMapper.subscribeRequest(selector);
            GrpcDiscoveryClient.DiscoveryStream opened = owner.grpc()
                    .openSubscription(request, new StreamObserver<SubscriptionEvent>() {
                        @Override
                        public void onNext(SubscriptionEvent value) {
                            handleEvent(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            disconnected(t);
                        }

                        @Override
                        public void onCompleted() {
                            disconnected(null);
                        }
                    });
            stream = opened;
            if (closed.get()) {
                opened.cancel(); // close 与开流竞态：close 时 stream 尚未赋值，补 cancel
            }
        } catch (RuntimeException e) {
            disconnected(e);
        }
    }

    private void handleEvent(SubscriptionEvent event) {
        if (closed.get()) {
            return;
        }
        owner.grpc().onTransportSucceeded();
        if (!event.hasService()) { // 首条空事件 = 建立确认
            if (!connected) {
                connected = true;
                reconnectAttempt.set(0);
                LOG.info("discovery subscription established ({})", selector);
            }
            owner.enqueue(() -> startRefresh("bootstrap", true));
            return;
        }
        ServiceChange change;
        try {
            change = ProtoMapper.fromProto(event.getService());
        } catch (RuntimeException e) {
            LOG.warn("malformed discovery push for {} ignored, scheduling refresh: {}", selector, e.toString());
            owner.enqueue(() -> startRefresh("malformed", false));
            return;
        }
        String cluster = event.getCluster();
        if (!owner.enqueue(() -> applyEvent(cluster, change))) {
            // applier 队列满：丢弃本次推送，由对账/周期刷新兜底
            LOG.warn("discovery applier queue full, push dropped for {}", change.key);
        }
    }

    private void disconnected(Throwable error) {
        if (closed.get()) {
            return;
        }
        connected = false;
        LOG.warn(
                "discovery subscription disconnected ({}): {}",
                selector,
                error == null ? "stream completed" : error.toString());
        if (!isSlowConsumer(error)) {
            // 传输级失败：共享 channel 去重熔断/重建（慢消费者断流时 channel 健康，仅本订阅重连）
            owner.grpc().onStreamFailure(owner.grpc().currentAddress());
        }
        owner.registry()
                .counter(
                        "artemis.client.discovery.subscribe.reconnect",
                        "client",
                        owner.clientId(),
                        "subscription",
                        subscriptionTag)
                .increment();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = backoff(reconnectAttempt.getAndIncrement());
        try {
            reconnectFuture = owner.scheduler()
                    .schedule(
                            () -> {
                                reconnectScheduled.set(false);
                                connect();
                            },
                            delay,
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled.set(false);
            LOG.warn("discovery reconnect scheduling rejected: {}", e.toString());
        }
    }

    /** 慢消费者断流（server 端 isReady 背压）：channel 健康，仅本订阅重连，不触发熔断换址。 */
    private static boolean isSlowConsumer(Throwable error) {
        return error instanceof io.grpc.StatusRuntimeException
                && ((io.grpc.StatusRuntimeException) error).getStatus().getCode()
                        == io.grpc.Status.Code.RESOURCE_EXHAUSTED;
    }

    private long backoff(int attempt) {
        long base = reconnectBaseMs.getValue();
        long max = reconnectMaxMs.getValue();
        double delay = base * Math.pow(2, Math.min(attempt, 10));
        delay = Math.min(max, delay);
        long jittered = (long) (delay * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4));
        return Math.max(1, jittered);
    }

    // ---- 全量刷新（bootstrap/周期/对账触发）----

    private void requestRefresh(String trigger) {
        if (!owner.enqueue(() -> startRefresh(trigger, false))) {
            LOG.warn("discovery refresh request dropped (applier queue full), trigger={}", trigger);
        }
    }

    /** applier 线程：启动一次全量 Query；force = 覆盖进行中的刷新（重连后重新基线）。 */
    private void startRefresh(String trigger, boolean force) {
        if (closed.get()) {
            return;
        }
        if (refreshing && !force) {
            refreshRequested = true;
            return;
        }
        refreshing = true;
        refreshRequested = false;
        buffer.clear();
        final int generation = ++refreshGeneration;
        owner.grpc().query(ProtoMapper.queryRequest(selector), new GrpcDiscoveryClient.Callback<QueryResponse>() {
            @Override
            public void onSuccess(QueryResponse response) {
                enqueueCompletion(() -> finishRefresh(generation, trigger, response));
            }

            @Override
            public void onError(Throwable error) {
                enqueueCompletion(() -> failRefresh(generation, trigger, error));
            }
        });
    }

    /**
     * 刷新完成/失败任务入 applier；队列满时经 scheduler 退避重试（不得静默丢弃——
     * refreshing 标志留在 applier 线程，丢失完成任务会使订阅刷新永久停摆）。
     */
    private void enqueueCompletion(final Runnable task) {
        if (owner.enqueue(task)) {
            return;
        }
        retryCompletion(task, 1);
    }

    private void retryCompletion(final Runnable task, final int attempt) {
        if (closed.get() || attempt > 20) {
            LOG.warn("discovery refresh completion dropped after {} attempt(s) ({})", attempt, selector);
            return;
        }
        try {
            owner.scheduler()
                    .schedule(
                            () -> {
                                if (closed.get()) {
                                    return;
                                }
                                if (!owner.enqueue(task)) {
                                    retryCompletion(task, attempt + 1);
                                }
                            },
                            50,
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.warn("discovery refresh completion retry scheduling rejected: {}", e.toString());
        }
    }

    private void finishRefresh(int generation, String trigger, QueryResponse response) {
        if (closed.get() || generation != refreshGeneration) {
            return;
        }
        if (response.getStatus().getAck() == Ack.ACK_FAIL) {
            failRefreshInternal(
                    generation,
                    trigger,
                    "refresh rejected: " + response.getStatus().getError());
            return;
        }
        try {
            applyUpstreams(response.getData().getUpstreamsList());
            Set<ServiceId> changed =
                    new LinkedHashSet<>(cache.replaceAll(ProtoMapper.clusterEntries(response.getData())));
            replayBuffer(changed);
            refreshing = false;
            snapshotReplay = false;
            notifyChanged(changed);
            if (!changed.isEmpty()) {
                scheduleSnapshot();
            }
            refreshMetric(trigger, "success");
            LOG.debug("discovery refresh succeeded ({}), {} changed service(s)", trigger, changed.size());
        } catch (RuntimeException e) {
            // 响应映射/应用异常（如异常实例数据）：按失败收敛，绝不让 refreshing 悬空
            failRefreshInternal(generation, trigger, "refresh apply failed: " + e);
            return;
        }
        if (refreshRequested) {
            refreshRequested = false;
            startRefresh(trigger, false);
        }
    }

    private void failRefresh(int generation, String trigger, Throwable error) {
        if (closed.get() || generation != refreshGeneration) {
            return;
        }
        failRefreshInternal(generation, trigger, error == null ? "unknown" : error.toString());
    }

    private void failRefreshInternal(int generation, String trigger, String reason) {
        if (closed.get() || generation != refreshGeneration) {
            return;
        }
        refreshing = false;
        Set<ServiceId> changed = new LinkedHashSet<>();
        replayBuffer(changed); // 不丢实时变更；本地视图保持陈旧可用
        notifyChanged(changed);
        if (!changed.isEmpty()) {
            scheduleSnapshot();
        }
        refreshMetric(trigger, "failure");
        LOG.warn("discovery refresh failed ({}): {}", trigger, reason);
        if (refreshRequested || "bootstrap".equals(trigger)) {
            refreshRequested = false;
            scheduleRefreshRetry(trigger);
        }
    }

    private void scheduleRefreshRetry(String trigger) {
        try {
            refreshRetryFuture = owner.scheduler()
                    .schedule(
                            () -> owner.enqueue(() -> startRefresh(trigger, false)),
                            reconnectBaseMs.getValue(),
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.warn("discovery refresh retry scheduling rejected: {}", e.toString());
        }
    }

    private void replayBuffer(Set<ServiceId> changed) {
        while (!buffer.isEmpty()) {
            BufferedChange buffered = buffer.pollFirst();
            if (cache.applyChange(buffered.cluster, buffered.change)) {
                changed.add(buffered.change.key);
            }
        }
    }

    private void applyEvent(String cluster, ServiceChange change) {
        if (closed.get()) {
            return;
        }
        if (refreshing) {
            if (buffer.size() >= MAX_EVENT_BUFFER) {
                buffer.pollFirst();
                owner.registry()
                        .counter(
                                "artemis.client.discovery.push.buffer.dropped",
                                "client",
                                owner.clientId(),
                                "subscription",
                                subscriptionTag)
                        .increment();
            }
            buffer.addLast(new BufferedChange(cluster, change));
            return;
        }
        if (cache.applyChange(cluster, change)) {
            owner.registry()
                    .counter(
                            "artemis.client.discovery.push",
                            "client",
                            owner.clientId(),
                            "subscription",
                            subscriptionTag)
                    .increment();
            notifyChanged(change.key);
            scheduleSnapshot();
        }
    }

    // ---- 对账（60s checksum 表比对）----

    private void scheduleReconcile() {
        if (closed.get()) {
            return;
        }
        try {
            reconcileFuture = owner.scheduler()
                    .schedule(
                            () -> {
                                if (closed.get()) {
                                    return;
                                }
                                runChecksum();
                                scheduleReconcile();
                            },
                            reconcileIntervalMs.getValue(),
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.warn("discovery reconcile scheduling rejected: {}", e.toString());
        }
    }

    private void runChecksum() {
        owner.grpc()
                .checksum(ProtoMapper.checksumRequest(selector), new GrpcDiscoveryClient.Callback<ChecksumResponse>() {
                    @Override
                    public void onSuccess(ChecksumResponse response) {
                        owner.enqueue(() -> applyChecksum(response));
                    }

                    @Override
                    public void onError(Throwable error) {
                        reconcileMetric("failure");
                        LOG.debug("discovery checksum failed ({}): {}", selector, error.toString());
                    }
                });
    }

    private void applyChecksum(ChecksumResponse response) {
        if (closed.get()) {
            return;
        }
        if (response.getStatus().getAck() == Ack.ACK_FAIL) {
            reconcileMetric("failure");
            return;
        }
        applyUpstreams(response.getData().getUpstreamsList());
        Map<String, Map<ServiceId, Integer>> remote = new LinkedHashMap<>();
        for (ClusterChecksums cluster : response.getData().getClustersList()) {
            Map<ServiceId, Integer> perCluster = new LinkedHashMap<>();
            for (ServiceChecksum service : cluster.getServicesList()) {
                perCluster.put(ProtoMapper.fromProto(service.getKey()), service.getChecksum());
            }
            remote.put(cluster.getCluster(), perCluster);
        }
        boolean mismatch = checksumMismatch(cache.checksums(), remote);
        reconcileMetric(mismatch ? "mismatch" : "success");
        if (mismatch) {
            LOG.info("discovery checksum mismatch ({}), triggering full refresh", selector);
            requestRefresh("checksum");
        }
    }

    private static boolean checksumMismatch(
            Map<String, Map<ServiceId, Integer>> local, Map<String, Map<ServiceId, Integer>> remote) {
        Set<String> clusters = new LinkedHashSet<>(local.keySet());
        clusters.addAll(remote.keySet());
        for (String cluster : clusters) {
            Map<ServiceId, Integer> localServices = local.get(cluster);
            Map<ServiceId, Integer> remoteServices = remote.get(cluster);
            Set<ServiceId> services = new LinkedHashSet<>();
            if (localServices != null) {
                services.addAll(localServices.keySet());
            }
            if (remoteServices != null) {
                services.addAll(remoteServices.keySet());
            }
            for (ServiceId service : services) {
                int localChecksum = valueOf(localServices, service);
                int remoteChecksum = valueOf(remoteServices, service);
                if (localChecksum != remoteChecksum) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int valueOf(Map<ServiceId, Integer> checksums, ServiceId service) {
        if (checksums == null) {
            return 0;
        }
        Integer value = checksums.get(service);
        return value == null ? 0 : value;
    }

    // ---- 周期全量刷新（15min 最终兜底）----

    private void scheduleRefreshLoop() {
        if (closed.get()) {
            return;
        }
        try {
            refreshLoopFuture = owner.scheduler()
                    .schedule(
                            () -> {
                                if (closed.get()) {
                                    return;
                                }
                                requestRefresh("periodic");
                                scheduleRefreshLoop();
                            },
                            refreshIntervalMs.getValue(),
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.warn("discovery refresh loop scheduling rejected: {}", e.toString());
        }
    }

    // ---- 辅助 ----

    private void applyUpstreams(List<UpstreamStatus> statuses) {
        if (statuses.isEmpty()) {
            return;
        }
        Map<String, UpstreamState> updated = new LinkedHashMap<>();
        for (UpstreamStatus status : statuses) {
            updated.put(status.getCluster(), ProtoMapper.fromProto(status.getState()));
        }
        upstreamStates = Collections.unmodifiableMap(updated);
    }

    private void notifyChanged(Set<ServiceId> changed) {
        for (ServiceId service : changed) {
            notifyChanged(service);
        }
    }

    private void notifyChanged(ServiceId service) {
        owner.listenerDispatcher().notify(listener, service, subscriptionTag);
    }

    private void scheduleSnapshot() {
        if (closed.get()) {
            return;
        }
        changeEpoch.incrementAndGet();
        owner.snapshotWriter().request(this);
    }

    private void refreshMetric(String trigger, String outcome) {
        owner.registry()
                .counter(
                        "artemis.client.discovery.refresh",
                        "client",
                        owner.clientId(),
                        "subscription",
                        subscriptionTag,
                        "trigger",
                        trigger,
                        "outcome",
                        outcome)
                .increment();
    }

    private void reconcileMetric(String outcome) {
        owner.registry()
                .counter(
                        "artemis.client.discovery.reconcile",
                        "client",
                        owner.clientId(),
                        "subscription",
                        subscriptionTag,
                        "outcome",
                        outcome)
                .increment();
    }
}
