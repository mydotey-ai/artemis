package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoMapper;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleListener;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleSource;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleWatch;
import org.mydotey.ai.artemis.proto.management.v1.AckEjectionsRequest;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;

/**
 * management 摘除规则流 gRPC 实现（领域端口 {@link EjectionRuleSource}）。
 * 端点轮换 / channel 池 / 认证 / 订阅方标识 / ack 端点策略见
 * {@link ManagementRuleChannels}（#10 收口，与路由规则流共享设施形态）。
 * 规则量小（管理操作级），单流无分片；keepalive 检测半开连接。回调只分派
 * listener（应用逻辑在同步器线程）。
 */
@Slf4j
public final class GrpcEjectionRuleSource implements EjectionRuleSource, AutoCloseable {

    private static final long ACK_DEADLINE_MS = 3_000;

    private final ManagementRuleChannels channels;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public GrpcEjectionRuleSource(ManagementConfig config, ManagementAuthConfig auth) {
        this.channels = new ManagementRuleChannels(config, auth);
    }

    @Override
    public EjectionRuleWatch watch(EjectionRuleListener listener) {
        GrpcEjectionRuleWatch watch = new GrpcEjectionRuleWatch(listener, mapper);
        watch.start(
                channels.channel(channels.nextWatchEndpoint())
                        .newCall(EjectionRuleServiceGrpc.getWatchEjectionsMethod(), CallOptions.DEFAULT),
                EjectionRulesRequest.newBuilder()
                        .setSubscriberId(channels.subscriberId())
                        .build());
        return watch;
    }

    /** 生效 ack（phase-5）：短超时 unary，异常上抛由调用方 WARN 容忍。 */
    @Override
    public void ack(long appliedVersion) {
        EjectionRuleServiceGrpc.newBlockingStub(channels.channel(channels.ackEndpoint()))
                .withDeadlineAfter(ACK_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .ackEjections(AckEjectionsRequest.newBuilder()
                        .setSubscriberId(channels.subscriberId())
                        .setAppliedVersion(appliedVersion)
                        .build());
    }

    @Override
    public void close() {
        channels.close();
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
