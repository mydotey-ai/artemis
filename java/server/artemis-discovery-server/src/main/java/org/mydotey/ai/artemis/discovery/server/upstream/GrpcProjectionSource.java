package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoMapper;
import org.mydotey.ai.artemis.discovery.domain.ProjectionListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSession;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSource;
import org.mydotey.ai.artemis.discovery.domain.ProjectionWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.projection.v1.ProjectionServiceGrpc;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;
import org.mydotey.ai.artemis.proto.projection.v1.WatchRequest;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 上游投影数据源 gRPC 实现（领域端口 {@link ProjectionSource}）。
 *
 * <p>端点轮换（D-D4）：{@link #open} 跨轮 round-robin 选端点；一轮会话的 watch 与
 * bootstrap 绑定同一端点（同端注册表视图，D-D3 论证前提），失败由同步器整轮重来换端点。
 *
 * <p>channel per-endpoint 惰性建、全生命周期复用（认证头每次调用现取，动态可轮换）；
 * bootstrap 分片可能接近 16 MiB，接收上限显式提升。
 */
public final class GrpcProjectionSource implements ProjectionSource, AutoCloseable {

    static final String KEY_BOOTSTRAP_TIMEOUT_MS = "artemis.discovery.upstream.bootstrapTimeoutMs";
    static final String KEY_MAX_INBOUND_MESSAGE_BYTES = "artemis.discovery.upstream.maxInboundMessageBytes";

    private static final long DEFAULT_BOOTSTRAP_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final UpstreamConfig.Upstream upstream;
    private final UpstreamAuthConfig auth;
    private final Property<String, Long> bootstrapTimeoutMs;
    private final int maxInboundMessageBytes;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final ConcurrentHashMap<UpstreamConfig.Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public GrpcProjectionSource(
            StringProperties properties, UpstreamConfig.Upstream upstream, UpstreamAuthConfig auth) {
        this.upstream = upstream;
        this.auth = auth;
        this.bootstrapTimeoutMs = ScfProperties.staticLong(
                properties,
                KEY_BOOTSTRAP_TIMEOUT_MS,
                DEFAULT_BOOTSTRAP_TIMEOUT_MS,
                1_000,
                600_000,
                "upstream bootstrap stream deadline (static)");
        this.maxInboundMessageBytes = ScfProperties.staticInt(
                        properties,
                        KEY_MAX_INBOUND_MESSAGE_BYTES,
                        DEFAULT_MAX_INBOUND_MESSAGE_BYTES,
                        1024 * 1024,
                        64 * 1024 * 1024,
                        "max inbound message bytes for upstream channel (static)")
                .getValue();
    }

    @Override
    public ProjectionSession open(String requesterId) {
        List<UpstreamConfig.Endpoint> endpoints = upstream.endpoints();
        int index = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
        return new GrpcProjectionSession(endpoints.get(index), requesterId);
    }

    private ManagedChannel channel(UpstreamConfig.Endpoint endpoint) {
        return channels.computeIfAbsent(
                endpoint,
                ignored -> ManagedChannelBuilder.forAddress(endpoint.host(), endpoint.port())
                        .usePlaintext()
                        .maxInboundMessageSize(maxInboundMessageBytes)
                        // watch 是被动长流，注册表无变更时零流量：keepalive ping 检测半开连接
                        // （NAT 静默丢弃）。间隔不低于服务端默认 permitKeepAlive（5min），
                        // 避免 too-many-pings GOAWAY；最迟约 5min + timeout 感知并断流重连
                        .keepAliveTime(310, TimeUnit.SECONDS)
                        .keepAliveTimeout(20, TimeUnit.SECONDS)
                        .build());
    }

    /** 认证头按调用现取（动态配置、运行期可轮换）；凭证不完整视为未配置，不附残缺头。 */
    private io.grpc.Channel upstreamChannel(UpstreamConfig.Endpoint endpoint) {
        ManagedChannel channel = channel(endpoint);
        if (!auth.enabled()) {
            return channel;
        }
        String headerValue = BasicAuth.headerValueOrNull(auth.username(), auth.password());
        if (headerValue == null) {
            return channel;
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), headerValue);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }

    /** 单端点会话：watch（async）与 bootstrap（blocking stream）共用 channel 与认证。 */
    private final class GrpcProjectionSession implements ProjectionSession {

        private final UpstreamConfig.Endpoint endpoint;
        private final String requesterId;
        private final AtomicReference<GrpcProjectionWatch> watch = new AtomicReference<>();

        GrpcProjectionSession(UpstreamConfig.Endpoint endpoint, String requesterId) {
            this.endpoint = endpoint;
            this.requesterId = requesterId;
        }

        @Override
        public ProjectionWatch watch(String subscriberId, ProjectionListener listener) {
            GrpcProjectionWatch projectionWatch = new GrpcProjectionWatch(listener);
            if (!watch.compareAndSet(null, projectionWatch)) {
                throw new IllegalStateException("session already watching");
            }
            projectionWatch.start(
                    upstreamChannel(endpoint).newCall(ProjectionServiceGrpc.getWatchMethod(), CallOptions.DEFAULT),
                    WatchRequest.newBuilder().setSubscriberId(subscriberId).build());
            return projectionWatch;
        }

        @Override
        public List<ServiceInstances> bootstrap() {
            Iterator<BootstrapResponse> responses = ProjectionServiceGrpc.newBlockingStub(upstreamChannel(endpoint))
                    .withDeadlineAfter(bootstrapTimeoutMs.getValue(), TimeUnit.MILLISECONDS)
                    .bootstrap(BootstrapRequest.newBuilder()
                            .setRequesterId(requesterId)
                            .build());
            List<ServiceInstances> services = new ArrayList<>();
            while (responses.hasNext()) {
                BootstrapResponse response = responses.next();
                if (response.getStatus().getAck() != Ack.ACK_SUCCESS) {
                    throw new IllegalStateException("upstream " + endpoint + " returned bootstrap failure");
                }
                for (org.mydotey.ai.artemis.proto.common.v1.ServiceInstances service :
                        response.getData().getServicesList()) {
                    services.add(mapper.toDomain(service));
                }
            }
            return List.copyOf(services);
        }

        @Override
        public void close() {
            GrpcProjectionWatch current = watch.get();
            if (current != null) {
                current.close();
            }
        }
    }

    /** watch 流适配：close 后吞后续回调（gRPC cancel 竞态防御，端口契约「close 后不回调」）。 */
    private static final class GrpcProjectionWatch implements ProjectionWatch {

        private final ProjectionListener listener;
        private volatile io.grpc.ClientCall<WatchRequest, WatchEvent> call;
        private volatile boolean closed;

        GrpcProjectionWatch(ProjectionListener listener) {
            this.listener = listener;
        }

        void start(io.grpc.ClientCall<WatchRequest, WatchEvent> clientCall, WatchRequest request) {
            if (closed) {
                return;
            }
            call = clientCall;
            io.grpc.stub.ClientCalls.asyncServerStreamingCall(clientCall, request, new StreamObserver<WatchEvent>() {
                @Override
                public void onNext(WatchEvent event) {
                    if (closed) {
                        return;
                    }
                    if (DiscoveryProtoMapper.INSTANCE.isEstablishment(event)) {
                        listener.onEstablished();
                    } else {
                        listener.onChanges(DiscoveryProtoMapper.INSTANCE.toDomain(event.getChanges()));
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (closed) {
                        return;
                    }
                    listener.onClosed();
                }

                @Override
                public void onCompleted() {
                    if (closed) {
                        return;
                    }
                    listener.onClosed();
                }
            });
        }

        @Override
        public void close() {
            closed = true;
            io.grpc.ClientCall<WatchRequest, WatchEvent> current = call;
            if (current != null) {
                current.cancel("session closed", null);
            }
        }
    }
}
