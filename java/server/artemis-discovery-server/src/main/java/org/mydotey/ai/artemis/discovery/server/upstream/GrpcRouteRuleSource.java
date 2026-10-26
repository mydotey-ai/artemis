package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
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
 * 端点轮换 / channel 池 / 认证 / 订阅方标识 / ack 端点策略见
 * {@link ManagementRuleChannels}（#10 收口，与摘除规则流共享设施形态）。
 */
@Slf4j
public final class GrpcRouteRuleSource implements RouteRuleSource, AutoCloseable {

    private static final long ACK_DEADLINE_MS = 3_000;

    private final ManagementRuleChannels channels;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public GrpcRouteRuleSource(ManagementConfig config, ManagementAuthConfig auth) {
        this.channels = new ManagementRuleChannels(config, auth);
    }

    @Override
    public RouteRuleWatch watch(RouteRuleListener listener) {
        GrpcRouteRuleWatch watch = new GrpcRouteRuleWatch(listener, mapper);
        watch.start(
                channels.channel(channels.nextWatchEndpoint())
                        .newCall(RouteRuleServiceGrpc.getWatchRouteRulesMethod(), CallOptions.DEFAULT),
                RouteRulesRequest.newBuilder()
                        .setSubscriberId(channels.subscriberId())
                        .build());
        return watch;
    }

    /** 生效 ack：短超时 unary，异常上抛由调用方 WARN 容忍。 */
    @Override
    public void ack(long appliedVersion) {
        RouteRuleServiceGrpc.newBlockingStub(channels.channel(channels.ackEndpoint()))
                .withDeadlineAfter(ACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .ackRouteRules(AckRouteRulesRequest.newBuilder()
                        .setSubscriberId(channels.subscriberId())
                        .setAppliedVersion(appliedVersion)
                        .build());
    }

    @Override
    public void close() {
        channels.close();
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
