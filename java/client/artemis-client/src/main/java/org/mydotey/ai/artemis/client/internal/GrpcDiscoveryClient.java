package org.mydotey.ai.artemis.client.internal;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * discovery gRPC 传输（客户端多订阅共享）：channel 懒建 + 地址供给（最近成功优先/熔断）、
 * Basic 认证、订阅 server-stream 与 unary Query/Checksum（异步回调，10s deadline，不阻塞调度线程）。
 * 断流/开流失败由订阅层上报 {@link #onTransportFailed()}（熔断计数仅由订阅流驱动，与注册侧同归因纪律）。
 */
final class GrpcDiscoveryClient {

    /** unary 异步回调（onNext 至多一次；无响应按错误处理）。 */
    interface Callback<T> {
        void onSuccess(T response);

        void onError(Throwable error);
    }

    private static final Logger LOG = LoggerFactory.getLogger(GrpcDiscoveryClient.class);

    private static final long DEADLINE_MS = 10_000;

    private final ServerAddressProvider addressProvider;
    private final ClientInterceptor authInterceptor;

    private volatile ManagedChannel channel;
    private volatile String currentAddress;
    /** 刚断开的地址：重建 channel 时选址排除（换址）；建立后清空。 */
    private volatile String lastDisconnectedAddress;

    private volatile boolean closed;

    GrpcDiscoveryClient(ServerAddressProvider addressProvider, StringProperties properties) {
        this.addressProvider = addressProvider;
        this.authInterceptor = new AuthInterceptor(
                ClientProperties.authUsername(properties), ClientProperties.authPassword(properties));
    }

    /** 当前 channel（无则按选址建立；认证 interceptor 挂 channel 级，全部 RPC 覆盖）。close 后不再建立。 */
    ManagedChannel channel() {
        return channel(null);
    }

    /**
     * channel 获取：无/已关闭则重建（选址排除 lastDisconnectedAddress）；forcedExclude 非空时强制
     * 重新选址排除之（换址）。被替换的活 channel 异步 shutdown（其上的旧流由订阅层作废旧回调）。
     */
    private ManagedChannel channel(String forcedExclude) {
        ManagedChannel current = channel;
        if (forcedExclude != null || current == null || current.isShutdown()) {
            ManagedChannel replaced = null;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("discovery client closed");
                }
                current = channel;
                if (forcedExclude != null || current == null || current.isShutdown()) {
                    String exclude = forcedExclude != null ? forcedExclude : lastDisconnectedAddress;
                    String address = addressProvider.select(exclude);
                    if (current != null && !current.isShutdown() && address.equals(currentAddress)) {
                        return current;
                    }
                    replaced = current;
                    // keepAlive：半开连接（NAT 静默丢包）由 TCP keepalive 探测兜底
                    current = ManagedChannelBuilder.forTarget(address)
                            .usePlaintext()
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .keepAliveTimeout(10, TimeUnit.SECONDS)
                            .keepAliveWithoutCalls(true)
                            .intercept(authInterceptor)
                            .build();
                    channel = current;
                    currentAddress = address;
                    lastDisconnectedAddress = null;
                }
            }
            if (replaced != null) {
                replaced.shutdown();
            }
        }
        return current;
    }

    /** 当前连接地址（断流归因；无连接为 null）。 */
    String currentAddress() {
        return currentAddress;
    }

    /**
     * 共享 channel 的流级失败（多订阅去重）：仅当失败地址仍是当前 channel 地址时计入一次熔断并
     * 重建 channel；同一 channel 上的其它流随后收到的连带错误（currentAddress 已变/null）直接忽略。
     * 慢消费者断流（RESOURCE_EXHAUSTED）由订阅层单独处理，不经过本方法（channel 健康）。
     */
    void onStreamFailure(String failedAddress) {
        ManagedChannel old;
        synchronized (this) {
            if (failedAddress == null
                    || !failedAddress.equals(currentAddress)
                    || channel == null
                    || channel.isShutdown()) {
                return; // 陈旧流 / 已由其它订阅重建
            }
            addressProvider.markFailed(failedAddress);
            old = channel;
            channel = null;
            lastDisconnectedAddress = failedAddress;
        }
        if (old != null) {
            old.shutdown();
        }
    }

    /** 传输成功上报（订阅确认/推送）：清零节点失败计数并解除熔断。 */
    void onTransportSucceeded() {
        addressProvider.markSucceeded(currentAddress);
    }

    void query(QueryRequest request, final Callback<QueryResponse> callback) {
        try {
            DiscoveryQueryServiceGrpc.newStub(channel())
                    .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .query(request, unaryObserver(callback));
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }

    void checksum(ChecksumRequest request, final Callback<ChecksumResponse> callback) {
        try {
            DiscoveryReconciliationServiceGrpc.newStub(channel())
                    .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .checksum(request, unaryObserver(callback));
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }

    private static <T> StreamObserver<T> unaryObserver(final Callback<T> callback) {
        return new StreamObserver<T>() {
            private T response;

            @Override
            public void onNext(T value) {
                response = value;
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }

            @Override
            public void onCompleted() {
                if (response != null) {
                    callback.onSuccess(response);
                } else {
                    callback.onError(new IllegalStateException("empty response"));
                }
            }
        };
    }

    /** 订阅流句柄（close 时 cancel）。 */
    static final class DiscoveryStream {

        private volatile ClientCallStreamObserver<SubscribeRequest> requestStream;
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            ClientCallStreamObserver<SubscribeRequest> stream = requestStream;
            if (stream != null) {
                stream.cancel("subscription closed", null);
            }
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    /** 开订阅 server-stream；返回句柄供 close cancel。 */
    DiscoveryStream openSubscription(final SubscribeRequest request, final StreamObserver<SubscriptionEvent> observer) {
        final DiscoveryStream stream = new DiscoveryStream();
        DiscoverySubscriptionServiceGrpc.newStub(channel())
                .subscribe(request, new ClientResponseObserver<SubscribeRequest, SubscriptionEvent>() {
                    @Override
                    public void beforeStart(ClientCallStreamObserver<SubscribeRequest> requestStream) {
                        stream.requestStream = requestStream;
                        if (stream.cancelled) {
                            requestStream.cancel("subscription closed", null);
                        }
                    }

                    @Override
                    public void onNext(SubscriptionEvent value) {
                        observer.onNext(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        observer.onError(t);
                    }

                    @Override
                    public void onCompleted() {
                        observer.onCompleted();
                    }
                });
        return stream;
    }

    void close() {
        ManagedChannel current;
        synchronized (this) {
            closed = true;
            current = channel;
            channel = null;
        }
        if (current != null) {
            try {
                current.shutdown();
                if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.shutdownNow();
            }
        }
        LOG.info("discovery grpc transport closed");
    }
}
