package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapResponseData;
import org.mydotey.ai.artemis.proto.projection.v1.ProjectionServiceGrpc;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;
import org.mydotey.ai.artemis.proto.projection.v1.WatchRequest;
import org.mydotey.ai.artemis.registry.application.ProjectionProtoFacade;
import org.mydotey.ai.artemis.registry.application.ProjectionProtoMapper;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriber;
import org.mydotey.ai.artemis.registry.domain.ServiceChange;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 接入层：内部 peer 端口的投影分发契约绑定（registry → discovery，架构 §2.2 通道③）。
 * 由 {@code PeerServerConfiguration} 显式绑定；不加 {@code @GrpcService}，且主 server 通过
 * service filter 排除内部服务。
 *
 * <p>readiness 门控：未就绪时 Bootstrap/Watch 返回 UNAVAILABLE（未就绪节点不充当数据源）。
 * 分片预算与 peer bootstrap 共用 {@link BootstrapShards}（同一注册表全量导出、同一消息尺寸约束）。
 */
@Slf4j
public final class ProjectionGrpcService extends ProjectionServiceGrpc.ProjectionServiceImplBase {

    private static final ResponseStatus SUCCESS =
            ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build();

    private final ProjectionProtoFacade facade;
    private final ReadinessGate readinessGate;
    private final Property<String, Integer> shardBytes;

    public ProjectionGrpcService(
            ProjectionProtoFacade facade, ReadinessGate readinessGate, StringProperties properties) {
        this.facade = facade;
        this.readinessGate = readinessGate;
        this.shardBytes = properties.getIntProperty(
                BootstrapShards.KEY_SHARD_BYTES,
                new RangeValueConfig<>(
                        BootstrapShards.DEFAULT_SHARD_BYTES,
                        BootstrapShards.MIN_SHARD_BYTES,
                        BootstrapShards.MAX_SHARD_BYTES));
    }

    @Override
    public void bootstrap(BootstrapRequest request, StreamObserver<BootstrapResponse> responseObserver) {
        if (!readinessGate.isReady()) {
            responseObserver.onError(notReady("bootstrap"));
            return;
        }
        log.info("projection bootstrap requested by '{}'", request.getRequesterId());
        try {
            BootstrapShards.send(
                    facade.bootstrapSnapshotStream().iterator(),
                    shardBytes.getValue(),
                    shard -> sendShard(shard, responseObserver));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL
                    .withCause(e)
                    .withDescription("projection bootstrap processing failed")
                    .asException());
        }
    }

    @Override
    public void watch(WatchRequest request, StreamObserver<WatchEvent> responseObserver) {
        if (!readinessGate.isReady()) {
            responseObserver.onError(notReady("watch"));
            return;
        }
        log.info("projection watch subscribed by '{}'", request.getSubscriberId());
        WatchSubscriber subscriber = new WatchSubscriber(responseObserver);
        ServerCallStreamObserver<WatchEvent> serverObserver = null;
        if (responseObserver instanceof ServerCallStreamObserver<WatchEvent> candidate) {
            serverObserver = candidate;
            serverObserver.setOnCancelHandler(() -> facade.unsubscribe(subscriber));
        }
        facade.subscribe(subscriber);
        // 取消可能早于 handler 注册/订阅登记：补一次显式注销，避免悬挂的订阅者与推送线程
        if (serverObserver != null && serverObserver.isCancelled()) {
            facade.unsubscribe(subscriber);
        }
    }

    private void sendShard(
            List<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> shard,
            StreamObserver<BootstrapResponse> responseObserver) {
        responseObserver.onNext(BootstrapResponse.newBuilder()
                .setStatus(SUCCESS)
                .setData(BootstrapResponseData.newBuilder().addAllServices(shard))
                .build());
    }

    private static Exception notReady(String operation) {
        return Status.UNAVAILABLE
                .withDescription("registry server not ready for projection " + operation)
                .asException();
    }

    /**
     * gRPC 流适配：dispatcher 单订阅者专属线程串行回调，故 onNext 串行；close 可能与在途 onNext
     * 并发（慢消费者由聚合线程断流），以锁串行化并置终态标记。
     */
    private static final class WatchSubscriber implements ProjectionSubscriber {

        private final StreamObserver<WatchEvent> observer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WatchSubscriber(StreamObserver<WatchEvent> observer) {
            this.observer = observer;
        }

        @Override
        public synchronized void onChanges(List<ServiceChange> batch) {
            if (closed.get()) {
                return;
            }
            observer.onNext(WatchEvent.newBuilder()
                    .setChanges(ProjectionProtoMapper.toProto(batch))
                    .build());
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                observer.onError(Status.UNAVAILABLE
                        .withDescription("projection stream closed by server")
                        .asException());
            }
        }
    }
}
