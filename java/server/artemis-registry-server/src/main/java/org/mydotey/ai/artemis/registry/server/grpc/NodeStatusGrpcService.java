package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeStatusServiceGrpc;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 接入层：对外节点状态 API（外部 gRPC 端口，SDK 三级容灾的节点列表来源）。
 * 与内部 `ReplicationService.GetNodeStatus` 同构；readiness 由 {@code ReadinessInterceptor}
 * 按方法名豁免（排障/引导信息面），外部认证仍然生效。
 */
@GrpcService
public final class NodeStatusGrpcService extends NodeStatusServiceGrpc.NodeStatusServiceImplBase {

    private final ReplicationProtoFacade facade;

    public NodeStatusGrpcService(ReplicationProtoFacade facade) {
        this.facade = facade;
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
}
