package org.mydotey.ai.artemis.client.internal;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳引擎：单条 gRPC bidi 流，进程级全量实例集周期上报（间隔 &lt; 服务端租约 TTL）。
 * seq 进程内单调递增；ack 只记录 lastAckSeq 不阻塞（超时重发，全量幂等自愈）。
 * 断流 = 指数退避重连（随机抖动防同步风暴），重连开流成功立即补一跳（服务端 upsert 自愈）；
 * 退避计数只在收到新流 ack 后归零——本地建流成功不代表服务端可达。
 * ack 看门狗感知半开连接（NAT 静默丢包时 onError 不会到来）：超过 ackTimeout 未收 ack 即判流失效。
 * 调度器单线程 lazy 创建（未 start 的 client 不占线程）；心跳与重连任务串行执行无竞态。
 */
final class HeartbeatChannel {

    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatChannel.class);

    static final String KEY_INTERVAL_MS = "artemis.client.heartbeat.intervalMs";
    static final String KEY_ACK_TIMEOUT_MS = "artemis.client.heartbeat.ackTimeoutMs";
    static final String KEY_RECONNECT_BASE_MS = "artemis.client.reconnect.baseDelayMs";
    static final String KEY_RECONNECT_MAX_MS = "artemis.client.reconnect.maxDelayMs";

    private static final long DEFAULT_INTERVAL_MS = 5_000;
    private static final long DEFAULT_ACK_TIMEOUT_MS = 30_000;
    private static final long DEFAULT_RECONNECT_BASE_MS = 1_000;
    private static final long DEFAULT_RECONNECT_MAX_MS = 30_000;
    private static final int MAX_PENDING_TASKS = 64;

    private final GrpcRegistryClient grpcClient;
    private final Supplier snapshotSupplier;
    private final MeterRegistry registry;
    private final Counter heartbeatFailure;
    private final Counter reconnectCounter;
    private final Gauge connectedGauge;
    /** 连接态 gauge 载体（0/1）——强引用防 Micrometer 弱引用回收。 */
    private final AtomicLong connected = new AtomicLong();

    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean periodicScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();

    /** 配置定义在本类（消费方），动态语义：使用处每次取最新值（周期任务自续式重读）。 */
    private final Property<String, Long> intervalMs;

    private final Property<String, Long> ackTimeoutMs;
    private final Property<String, Long> reconnectBaseMs;
    private final Property<String, Long> reconnectMaxMs;

    private volatile BoundedScheduledExecutor scheduler;
    private volatile StreamObserver<HeartbeatRequest> requestObserver;
    /** 流代数：陈旧流的迟到回调（onError/onCompleted）不得拆毁新流。 */
    private volatile int generation;

    private volatile long lastAckSeq = -1;
    /** 最近一次 ack 的墙钟毫秒（开流时置为当前时间作为宽限起点）。 */
    private volatile long lastAckMillis;

    private volatile boolean closed;

    HeartbeatChannel(
            GrpcRegistryClient grpcClient,
            Supplier snapshotSupplier,
            StringProperties properties,
            MeterRegistry registry,
            String clientId) {
        this.grpcClient = grpcClient;
        this.snapshotSupplier = snapshotSupplier;
        this.registry = registry;
        this.heartbeatFailure = registry.counter("artemis.client.heartbeat.failure", "client", clientId);
        this.reconnectCounter = registry.counter("artemis.client.reconnect", "client", clientId);
        this.connectedGauge = Gauge.builder("artemis.client.connected", connected, AtomicLong::doubleValue)
                .tag("client", clientId)
                .register(registry);
        this.intervalMs =
                properties.getLongProperty(KEY_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_INTERVAL_MS, 500L, 60_000L));
        this.ackTimeoutMs = properties.getLongProperty(
                KEY_ACK_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_ACK_TIMEOUT_MS, 1_000L, 300_000L));
        this.reconnectBaseMs = properties.getLongProperty(
                KEY_RECONNECT_BASE_MS, new RangeValueConfig<>(DEFAULT_RECONNECT_BASE_MS, 100L, 60_000L));
        this.reconnectMaxMs = properties.getLongProperty(
                KEY_RECONNECT_MAX_MS, new RangeValueConfig<>(DEFAULT_RECONNECT_MAX_MS, 1_000L, 300_000L));
    }

    /** 启动：立即开流 + 首跳，周期心跳自开流起自续。 */
    synchronized void start() {
        if (closed) {
            throw new IllegalStateException("heartbeat channel closed");
        }
        if (scheduler == null) {
            scheduler = new BoundedScheduledExecutor("heartbeat", 1, MAX_PENDING_TASKS, new NamedDaemonThreadFactory());
        }
        scheduler.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        openStreamAndSend();
                    }
                },
                0,
                TimeUnit.MILLISECONDS);
    }

    /** 最近一次 ack 的 seq（排障可观测；-1 = 尚无 ack）。 */
    long lastAckSeq() {
        return lastAckSeq;
    }

    /** 立即补投一次全量心跳（显式操作上报失败的自愈触发；幂等、与周期心跳串行）。 */
    void sendNow() {
        BoundedScheduledExecutor current = scheduler;
        if (current == null || closed) {
            return;
        }
        try {
            current.execute(new Runnable() {
                @Override
                public void run() {
                    if (!closed) {
                        send();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.debug("heartbeat sendNow rejected (queue full or closing)");
        }
    }

    void close() {
        closed = true;
        connected.set(0);
        generation++; // 作废一切在途回调
        BoundedScheduledExecutor current = scheduler;
        if (current != null) {
            current.shutdownNow();
            try {
                // 有界等待：确保调度线程不再发出心跳帧（尽力屏障，跨 HTTP/2 流顺序最终由 TTL 兜底）
                current.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        StreamObserver<HeartbeatRequest> observer = requestObserver;
        requestObserver = null;
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (RuntimeException e) {
                LOG.debug("heartbeat stream close exception: {}", e.getMessage());
            }
        }
        // gauge 随 client 生命周期移除（多 client 各自 tag 独立，close 后不残留）
        registry.remove(connectedGauge);
    }

    /** 周期心跳链：CAS 保证全局单链，每跳重读间隔（动态生效）。 */
    private void ensurePeriodic() {
        if (closed) {
            return;
        }
        if (periodicScheduled.compareAndSet(false, true)) {
            scheduleTick();
        }
    }

    private void scheduleTick() {
        BoundedScheduledExecutor current = scheduler;
        if (current == null) {
            periodicScheduled.set(false);
            return;
        }
        try {
            current.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            tick();
                        }
                    },
                    intervalMs.getValue(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.warn("heartbeat tick scheduling rejected (queue full or closing)");
            periodicScheduled.set(false);
        }
    }

    private void tick() {
        try {
            if (!closed) {
                checkAckLiveness();
                send();
            }
        } catch (RuntimeException e) {
            // 心跳构建/发送的意外异常不得杀死调度链（scheduleAtFixedRate 抛错即停的教训）
            LOG.warn("heartbeat tick unexpected error: {}", e.getMessage(), e);
            markBroken();
        } finally {
            if (closed) {
                periodicScheduled.set(false);
            } else {
                scheduleTick();
            }
        }
    }

    /** 半开连接看门狗：超过 ackTimeout 无 ack（含开流宽限期）即判流失效并重连。 */
    private void checkAckLiveness() {
        if (requestObserver == null || lastAckMillis == 0) {
            return;
        }
        long silentMs = System.currentTimeMillis() - lastAckMillis;
        if (silentMs > ackTimeoutMs.getValue()) {
            LOG.warn("heartbeat ack timeout after {}ms, treating stream as broken", silentMs);
            heartbeatFailure.increment();
            markBroken();
        }
    }

    private void send() {
        StreamObserver<HeartbeatRequest> observer = requestObserver;
        if (observer == null) {
            return; // 流不在：重连任务负责重建（tick 不抢开流，退避节奏由 reconnect 控制）
        }
        Map<ServiceId, List<ServiceInstance>> snapshot = snapshotSupplier.get();
        HeartbeatRequest request = ProtoMapper.heartbeatRequest(snapshot, seq.incrementAndGet());
        try {
            observer.onNext(request);
        } catch (RuntimeException e) {
            LOG.debug("heartbeat send failed, stream broken: {}", e.getMessage());
            heartbeatFailure.increment();
            markBroken();
        }
    }

    private void openStreamAndSend() {
        if (closed) {
            return;
        }
        boolean reconnecting = reconnectAttempt.get() > 0;
        // 先作废旧代数再拆 channel：旧流被 shutdown 触发的 onError 必须被判为陈旧回调
        final int currentGeneration = ++generation;
        if (reconnecting) {
            // 重连路径：废弃旧 channel，select 重新选址（多址列表轮换；v0.1 单址即重建）
            grpcClient.resetChannel();
        }
        StreamObserver<HeartbeatResponse> responseObserver = new StreamObserver<HeartbeatResponse>() {
            @Override
            public void onNext(HeartbeatResponse response) {
                if (currentGeneration != generation) {
                    return; // 陈旧流回调
                }
                if (response.hasData()) {
                    lastAckSeq = response.getData().getSeq();
                    lastAckMillis = System.currentTimeMillis();
                    reconnectAttempt.set(0); // 服务端确认可达后才归零退避计数
                }
            }

            @Override
            public void onError(Throwable error) {
                LOG.debug("heartbeat stream error: {}", error.getMessage());
                if (currentGeneration == generation) {
                    heartbeatFailure.increment();
                    markBroken();
                }
            }

            @Override
            public void onCompleted() {
                LOG.debug("heartbeat stream completed by server");
                if (currentGeneration == generation && !closed) {
                    heartbeatFailure.increment();
                    markBroken();
                }
            }
        };
        try {
            requestObserver = grpcClient.openHeartbeatStream(responseObserver);
        } catch (RuntimeException e) {
            LOG.debug("heartbeat stream open failed: {}", e.getMessage());
            requestObserver = null;
            heartbeatFailure.increment();
            scheduleReconnect();
            return;
        }
        lastAckMillis = System.currentTimeMillis(); // ack 宽限起点（半开连接检测起点）
        connected.set(1);
        if (reconnecting) {
            LOG.info("heartbeat stream reconnected");
            reconnectCounter.increment();
        }
        send(); // 开流成功立即补跳：断流期间丢失的上报由服务端 upsert 收敛
        ensurePeriodic();
    }

    private void markBroken() {
        requestObserver = null;
        connected.set(0);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return; // 已有重连任务排队
        }
        if (reconnectAttempt.get() == 0) {
            LOG.warn("heartbeat stream broken, reconnecting with backoff");
        }
        long delay = backoffDelayMs();
        BoundedScheduledExecutor current = scheduler;
        if (current == null) {
            reconnectScheduled.set(false);
            return;
        }
        try {
            current.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            reconnectScheduled.set(false);
                            openStreamAndSend();
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled.set(false); // executor 已关闭或队列满
        }
    }

    /** 指数退避 base*2^attempt（上限 max）× [0.8, 1.2) 抖动。 */
    private long backoffDelayMs() {
        long base = reconnectBaseMs.getValue();
        long max = reconnectMaxMs.getValue();
        long exponential = base;
        int attempt = reconnectAttempt.get();
        for (int i = 0; i < attempt && exponential < max; i++) {
            exponential = Math.min(exponential * 2, max);
        }
        reconnectAttempt.set(Math.min(attempt + 1, 30));
        double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
        long delay = (long) (exponential * jitter);
        return Math.max(Math.min(delay, max), 1);
    }

    /** 快照供给（本地实例集 → 心跳全量结构）。 */
    interface Supplier {
        Map<ServiceId, List<ServiceInstance>> get();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private int count;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "artemis-client-heartbeat-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    }
}
