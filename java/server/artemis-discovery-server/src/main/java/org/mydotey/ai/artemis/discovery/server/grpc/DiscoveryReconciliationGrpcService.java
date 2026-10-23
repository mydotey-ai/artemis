package org.mydotey.ai.artemis.discovery.server.grpc;

import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoFacade;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

/** 对账出口（外部端口）：一行委派 application proto 门面；readiness/认证由拦截器承担。 */
@GrpcService
public final class DiscoveryReconciliationGrpcService
        extends DiscoveryReconciliationServiceGrpc.DiscoveryReconciliationServiceImplBase {

    private final DiscoveryProtoFacade facade;

    public DiscoveryReconciliationGrpcService(DiscoveryProtoFacade facade) {
        this.facade = facade;
    }

    @Override
    public void checksum(ChecksumRequest request, io.grpc.stub.StreamObserver<ChecksumResponse> responseObserver) {
        responseObserver.onNext(facade.checksum(request));
        responseObserver.onCompleted();
    }
}
