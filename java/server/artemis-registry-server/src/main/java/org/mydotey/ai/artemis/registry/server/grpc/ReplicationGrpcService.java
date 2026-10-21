package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 接入层：内部 peer 端口的复制契约绑定。由 {@code PeerServerConfiguration} 显式绑定；
 * 不加 {@code @GrpcService}，且主 server 通过 {@code GrpcServerFactoryCustomizer} 的
 * service filter 排除内部服务（Boot 的服务发现会收集全部 BindableService，不能只靠注解）。
 *
 * <p>readiness 语义按方法区分：`GetNodeStatus` 始终可应答（供 peer 判定 UP）；
 * `Replicate`/`Bootstrap` 在未就绪时返回 UNAVAILABLE——未就绪节点不能接收复制事件、
 * 也不能充当数据源，发送侧按暂时失败保序重试。
 */
@Slf4j
public final class ReplicationGrpcService extends ReplicationServiceGrpc.ReplicationServiceImplBase {

    static final String KEY_SHARD_BYTES = "artemis.registry.bootstrap.shardBytes";

    private static final int DEFAULT_SHARD_BYTES = 1024 * 1024;
    private static final ResponseStatus SUCCESS =
            ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build();

    private final ReplicationProtoFacade facade;
    private final ReadinessGate readinessGate;
    private final Property<String, Integer> shardBytes;

    public ReplicationGrpcService(
            ReplicationProtoFacade facade, ReadinessGate readinessGate, StringProperties properties) {
        this.facade = facade;
        this.readinessGate = readinessGate;
        this.shardBytes = properties.getIntProperty(
                KEY_SHARD_BYTES, new RangeValueConfig<>(DEFAULT_SHARD_BYTES, 64 * 1024, 8 * 1024 * 1024));
    }

    @Override
    public void getNodeStatus(GetNodeStatusRequest request, StreamObserver<GetNodeStatusResponse> responseObserver) {
        try {
            responseObserver.onNext(facade.getNodeStatus(request));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL
                    .withCause(e)
                    .withDescription("node status processing failed")
                    .asException());
        }
    }

    @Override
    public void replicate(ReplicateRequest request, StreamObserver<ReplicateResponse> responseObserver) {
        if (!readinessGate.isReady()) {
            responseObserver.onError(notReady("replication"));
            return;
        }
        try {
            responseObserver.onNext(facade.replicate(request));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL
                    .withCause(e)
                    .withDescription("replicate processing failed")
                    .asException());
        }
    }

    @Override
    public void bootstrap(BootstrapRequest request, StreamObserver<BootstrapResponse> responseObserver) {
        if (!readinessGate.isReady()) {
            responseObserver.onError(notReady("bootstrap"));
            return;
        }
        log.info("bootstrap snapshot requested by node '{}'", request.getRequesterNodeId());
        try {
            // 惰性逐 service 映射 + 分片：内存与单分片同阶，不物化整表 proto 副本
            Iterator<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> services =
                    facade.bootstrapSnapshotStream().iterator();
            int budget = shardBytes.getValue();
            List<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> shard = new ArrayList<>();
            int size = 0;
            while (services.hasNext()) {
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances service = services.next();
                int serviceSize = service.getSerializedSize();
                if (!shard.isEmpty() && size + serviceSize > budget) {
                    sendShard(shard, responseObserver);
                    shard = new ArrayList<>();
                    size = 0;
                }
                shard.add(service);
                size += serviceSize;
            }
            if (!shard.isEmpty()) {
                sendShard(shard, responseObserver);
            }
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL
                    .withCause(e)
                    .withDescription("bootstrap processing failed")
                    .asException());
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
                .withDescription("registry server not ready for " + operation)
                .asException();
    }
}
