package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoMapper;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleListener;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleSource;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleWatch;
import org.mydotey.ai.artemis.proto.management.v1.AckRouteRulesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RouteRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesRequest;

/**
 * management 路由规则流 gRPC 实现（领域端口 {@link RouteRuleSource}，phase-2）。
 * 端点轮换 / channel 池 / keepalive / 订阅方标识 / ack 同端点策略均同
 * {@link GrpcEjectionRuleSource}（同一 management 上游的两条独立规则流）。
 */
@Slf4j
public final class GrpcRouteRuleSource implements RouteRuleSource, AutoCloseable {

    private static final long ACK_DEADLINE_MS = 3_000;

    private final ManagementConfig config;
    private final ManagementAuthConfig auth;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final ConcurrentHashMap<UpstreamConfig.Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;
    private final String subscriberId = subscriberId(); // DNS 查找一次（重连轮次复用）
    private volatile UpstreamConfig.Endpoint watchEndpoint; // ack 必须送达持有订阅的端点

    public GrpcRouteRuleSource(ManagementConfig config, ManagementAuthConfig auth) {
        this.config = config;
        this.auth = auth;
    }

    @Override
    public RouteRuleWatch watch(RouteRuleListener listener) {
        List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
        int index = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
        final UpstreamConfig.Endpoint currentEndpoint = endpoints.get(index);
        watchEndpoint = currentEndpoint;
        GrpcRouteRuleWatch watch = new GrpcRouteRuleWatch(listener, mapper);
        watch.start(
                upstreamChannel(currentEndpoint)
                        .newCall(RouteRuleServiceGrpc.getWatchRouteRulesMethod(), CallOptions.DEFAULT),
                RouteRulesRequest.newBuilder().setSubscriberId(subscriberId).build());
        return watch;
    }

    /** 订阅方标识：host + 进程级短随机后缀——同 host 多节点部署水位条目不互撞。 */
    private static String subscriberId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown";
        }
        return "artemis-discovery@" + host + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** 生效 ack：优先走当前 watch 所在端点（订阅登记在该 management 进程）；短超时 unary，
     * 异常上抛由调用方 WARN 容忍。 */
    @Override
    public void ack(long appliedVersion) {
        UpstreamConfig.Endpoint endpoint = watchEndpoint;
        if (endpoint == null) { // 无活 watch（防御路径）：轮换兜底
            List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
            endpoint = endpoints.get(Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size()));
        }
        RouteRuleServiceGrpc.newBlockingStub(upstreamChannel(endpoint))
                .withDeadlineAfter(ACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .ackRouteRules(AckRouteRulesRequest.newBuilder()
                        .setSubscriberId(subscriberId)
                        .setAppliedVersion(appliedVersion)
                        .build());
    }

    /** 认证头按调用现取（动态配置、运行期可轮换）；凭证不完整视为未配置，不附残缺头。 */
    private io.grpc.Channel upstreamChannel(UpstreamConfig.Endpoint endpoint) {
        ManagedChannel channel = channels.computeIfAbsent(
                endpoint,
                ignored -> ManagedChannelBuilder.forAddress(endpoint.host(), endpoint.port())
                        .usePlaintext()
                        .maxInboundMessageSize(config.maxInboundMessageBytes())
                        // 规则流是被动长流：keepalive ping 检测半开连接（参数同投影上游）
                        .keepAliveTime(310, TimeUnit.SECONDS)
                        .keepAliveTimeout(20, TimeUnit.SECONDS)
                        .build());
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

    /** watch 流适配：close 后吞后续回调并 cancel 在途调用（模式同 ejection watch）。 */
    private static final class GrpcRouteRuleWatch implements RouteRuleWatch {

        private final RouteRuleListener listener;
        private final DiscoveryProtoMapper mapper;
        private volatile ClientCall<RouteRulesRequest, RouteRulesEvent> call;
        private volatile boolean closed;

        private GrpcRouteRuleWatch(RouteRuleListener listener, DiscoveryProtoMapper mapper) {
            this.listener = listener;
            this.mapper = mapper;
        }

        void start(ClientCall<RouteRulesRequest, RouteRulesEvent> clientCall, RouteRulesRequest request) {
            if (closed) {
                return;
            }
            call = clientCall;
            io.grpc.stub.ClientCalls.asyncServerStreamingCall(clientCall, request, new StreamObserver<>() {
                @Override
                public void onNext(RouteRulesEvent event) {
                    if (closed) {
                        return;
                    }
                    if (mapper.isSnapshot(event)) {
                        listener.onSnapshot(mapper.toDomain(event.getSnapshot()), event.getVersion());
                    } else if (event.hasUpserted()) {
                        listener.onUpserted(mapper.toDomain(event.getUpserted()), event.getVersion());
                    } else if (event.hasDeleted()) {
                        listener.onDeleted(mapper.toDomain(event.getDeleted()), event.getVersion());
                    }
                    // 三字段皆未设：无效事件丢弃（断流重连快照自愈）
                }

                @Override
                public void onError(Throwable t) {
                    if (!closed) {
                        listener.onClosed();
                    }
                }

                @Override
                public void onCompleted() {
                    if (!closed) {
                        listener.onClosed();
                    }
                }
            });
        }

        @Override
        public void close() {
            closed = true;
            ClientCall<RouteRulesRequest, RouteRulesEvent> current = call;
            if (current != null) {
                current.cancel("route rule watch closed", null);
            }
        }
    }
}
