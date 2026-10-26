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
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleListener;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleSource;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleWatch;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;

/**
 * management 摘除规则流 gRPC 实现（领域端口 {@link EjectionRuleSource}）。
 *
 * <p>端点轮换（每轮 round-robin）与 channel 池 per endpoint 惰性建、全生命周期复用
 * （模式同 {@link GrpcProjectionSource}）；规则量小（管理操作级），单流无分片，
 * keepalive 检测半开连接。回调只分派 listener（应用逻辑在同步器线程）。
 * phase-5：{@link #ack} 走当前 watch 端点 channel 池的短超时 unary（失败由调用方容忍）。
 */
@Slf4j
public final class GrpcEjectionRuleSource implements EjectionRuleSource, AutoCloseable {

    private static final long ACK_DEADLINE_MS = 3_000;

    private final ManagementConfig config;
    private final ManagementAuthConfig auth;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final ConcurrentHashMap<UpstreamConfig.Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;
    private final String subscriberId = subscriberId(); // DNS 查找一次（重连轮次复用）
    private volatile UpstreamConfig.Endpoint watchEndpoint; // ack 必须送达持有订阅的端点

    public GrpcEjectionRuleSource(ManagementConfig config, ManagementAuthConfig auth) {
        this.config = config;
        this.auth = auth;
    }

    @Override
    public EjectionRuleWatch watch(EjectionRuleListener listener) {
        List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
        int index = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
        final UpstreamConfig.Endpoint currentEndpoint = endpoints.get(index);
        watchEndpoint = currentEndpoint;
        GrpcEjectionRuleWatch watch = new GrpcEjectionRuleWatch(listener, mapper);
        watch.start(
                upstreamChannel(currentEndpoint)
                        .newCall(EjectionRuleServiceGrpc.getWatchEjectionsMethod(), CallOptions.DEFAULT),
                EjectionRulesRequest.newBuilder().setSubscriberId(subscriberId).build());
        return watch;
    }

    /** 订阅方标识：host + 进程级短随机后缀——同 host 多节点部署水位条目不互撞（phase-5）。 */
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

    /** 生效 ack（phase-5）：优先走当前 watch 所在端点（订阅登记在该 management 进程，
     * 换址窗口期多端点并存时 ack 轮换可能送达未持有订阅的端点而被忽略）；短超时 unary，
     * 异常上抛由调用方 WARN 容忍。 */
    @Override
    public void ack(long appliedVersion) {
        UpstreamConfig.Endpoint endpoint = watchEndpoint;
        if (endpoint == null) { // 无活 watch（防御路径）：轮换兜底
            List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
            endpoint = endpoints.get(Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size()));
        }
        EjectionRuleServiceGrpc.newBlockingStub(upstreamChannel(endpoint))
                .withDeadlineAfter(ACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .ackEjections(org.mydotey.ai.artemis.proto.management.v1.AckEjectionsRequest.newBuilder()
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

    /** watch 流适配：close 后吞后续回调并 cancel 在途调用（gRPC cancel 竞态防御，模式同投影 watch）。 */
    private static final class GrpcEjectionRuleWatch implements EjectionRuleWatch {

        private final EjectionRuleListener listener;
        private final DiscoveryProtoMapper mapper;
        private volatile ClientCall<EjectionRulesRequest, EjectionRulesEvent> call;
        private volatile boolean closed;

        private GrpcEjectionRuleWatch(EjectionRuleListener listener, DiscoveryProtoMapper mapper) {
            this.listener = listener;
            this.mapper = mapper;
        }

        void start(ClientCall<EjectionRulesRequest, EjectionRulesEvent> clientCall, EjectionRulesRequest request) {
            if (closed) {
                return;
            }
            call = clientCall;
            io.grpc.stub.ClientCalls.asyncServerStreamingCall(clientCall, request, new StreamObserver<>() {
                @Override
                public void onNext(EjectionRulesEvent event) {
                    if (closed) {
                        return;
                    }
                    if (mapper.isSnapshot(event)) {
                        listener.onSnapshot(mapper.toDomain(event.getSnapshot()), event.getVersion());
                    } else if (event.hasEjected()) {
                        listener.onEjected(mapper.toDomain(event.getEjected()), event.getVersion());
                    } else if (event.hasRecovered()) {
                        listener.onRecovered(mapper.toDomain(event.getRecovered()), event.getVersion());
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
            ClientCall<EjectionRulesRequest, EjectionRulesEvent> current = call;
            if (current != null) {
                current.cancel("rule watch closed", null);
            }
        }
    }
}
