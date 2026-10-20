package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;

/**
 * 接入层：内部 peer 端口的复制契约绑定。由 {@code PeerServerConfiguration} 显式绑定；
 * 不加 {@code @GrpcService}，且主 server 通过 {@code GrpcServerFactoryCustomizer} 的
 * service filter 排除内部服务（Boot 的服务发现会收集全部 BindableService，不能只靠注解）。
 * 本 phase 仅实现 GetNodeStatus；Replicate/Bootstrap 未覆写，gRPC 自然返回 UNIMPLEMENTED。
 */
public final class ReplicationGrpcService extends ReplicationServiceGrpc.ReplicationServiceImplBase {

    private final ReplicationProtoFacade facade;

    public ReplicationGrpcService(ReplicationProtoFacade facade) {
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
