package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.tls.GrpcTlsContexts;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoMapper;
import org.mydotey.ai.artemis.discovery.domain.ProjectionListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSession;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSource;
import org.mydotey.ai.artemis.discovery.domain.ProjectionWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstanceServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesRequest;

/**
 * management 逻辑实例流的投影源适配（v0.4 phase-4）：把「流首快照 + 增量」适配为
 * {@link ProjectionSource} 的「Watch 建立确认 → Bootstrap → 变更消费」两段序——
 * 流首快照即 bootstrap 数据（stash 后 onEstablished），消费 {@code ProjectionSynchronizer}
 * 全套（重连 diff 续推、订阅扇出、checksum、union）。
 *
 * <p>端点轮换（每轮 round-robin）与 channel 池 per endpoint 惰性建、全生命周期复用
 * （模式同 {@link GrpcEjectionRuleSource}/{@link GrpcProjectionSource}）。
 */
@Slf4j
public final class GrpcLogicalInstanceSource implements ProjectionSource, AutoCloseable {

    /** 保留 cluster id：逻辑实例在 discovery 投影中的来源标识（上游撞名启动失败）。 */
    public static final String LOGICAL_CLUSTER_ID = "logical";

    private final ManagementConfig config;
    private final ManagementAuthConfig auth;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final ConcurrentHashMap<UpstreamConfig.Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcLogicalInstanceSource(ManagementConfig config, ManagementAuthConfig auth) {
        this.config = config;
        this.auth = auth;
    }

    @Override
    public ProjectionSession open(String requesterId) {
        List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
        int index = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
        return new LogicalSession(endpoints.get(index));
    }

    /** 认证头按调用现取（动态配置、运行期可轮换）；凭证不完整视为未配置，不附残缺头。 */
    private io.grpc.Channel upstreamChannel(UpstreamConfig.Endpoint endpoint) {
        ManagedChannel channel = channels.computeIfAbsent(
                endpoint,
                ignored -> GrpcTlsContexts.channelForAddress(endpoint.host(), endpoint.port(), config.tls())
                        .maxInboundMessageSize(config.maxInboundMessageBytes())
                        // 被动长流：keepalive ping 检测半开连接（参数同规则流/投影上游）
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

    /** 单端点会话：流首快照 stash → onEstablished；bootstrap 返回 stash（established 后立即可得）。 */
    private final class LogicalSession implements ProjectionSession {

        private final UpstreamConfig.Endpoint endpoint;
        private final AtomicReference<GrpcLogicalWatch> watch = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<List<ServiceInstances>>> snapshot = new AtomicReference<>();

        LogicalSession(UpstreamConfig.Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public ProjectionWatch watch(String subscriberId, ProjectionListener listener) {
            GrpcLogicalWatch logicalWatch = new GrpcLogicalWatch(listener);
            if (!watch.compareAndSet(null, logicalWatch)) {
                throw new IllegalStateException("session already watching");
            }
            CompletableFuture<List<ServiceInstances>> future = new CompletableFuture<>();
            snapshot.set(future);
            logicalWatch.start(
                    upstreamChannel(endpoint)
                            .newCall(LogicalInstanceServiceGrpc.getWatchLogicalInstancesMethod(), CallOptions.DEFAULT),
                    LogicalInstancesRequest.newBuilder()
                            .setSubscriberId(subscriberId)
                            .build(),
                    future);
            return logicalWatch;
        }

        @Override
        public List<ServiceInstances> bootstrap() {
            try {
                // established 已保证完成（快照先 stash 后确认）；异常/中断 → 整轮重来
                return snapshot.get().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("logical instance snapshot interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("logical instance snapshot failed", e.getCause());
            }
        }

        @Override
        public void close() {
            GrpcLogicalWatch current = watch.get();
            if (current != null) {
                current.close();
            }
        }
    }

    /** watch 流适配：close 后吞后续回调并 cancel 在途调用（gRPC cancel 竞态防御，模式同投影 watch）。 */
    private static final class GrpcLogicalWatch implements ProjectionWatch {

        private final ProjectionListener listener;
        private volatile ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> call;
        private volatile boolean closed;

        private GrpcLogicalWatch(ProjectionListener listener) {
            this.listener = listener;
        }

        void start(
                ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> clientCall,
                LogicalInstancesRequest request,
                CompletableFuture<List<ServiceInstances>> snapshot) {
            if (closed) {
                return;
            }
            call = clientCall;
            ClientCalls.asyncServerStreamingCall(clientCall, request, new StreamObserver<>() {
                @Override
                public void onNext(LogicalInstancesEvent event) {
                    if (closed) {
                        return;
                    }
                    if (DiscoveryProtoMapper.INSTANCE.isSnapshot(event)) {
                        // 先 stash 后确认：synchronizer 在 established 后才调 bootstrap，立即可得
                        snapshot.complete(DiscoveryProtoMapper.INSTANCE.toDomain(event.getSnapshot()));
                        listener.onEstablished();
                    } else if (event.hasUpserted()) {
                        listener.onChanges(List.of(DiscoveryProtoMapper.INSTANCE.toUpsertChange(event.getUpserted())));
                    } else if (event.hasDeleted()) {
                        listener.onChanges(List.of(DiscoveryProtoMapper.INSTANCE.toDeleteChange(event.getDeleted())));
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
            ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> current = call;
            if (current != null) {
                current.cancel("logical instance watch closed", null);
            }
        }
    }
}
