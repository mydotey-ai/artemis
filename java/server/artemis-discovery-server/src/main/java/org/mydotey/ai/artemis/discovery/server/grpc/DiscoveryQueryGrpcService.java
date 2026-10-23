package org.mydotey.ai.artemis.discovery.server.grpc;

import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoFacade;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.springframework.grpc.server.service.GrpcService;

/** 查询出口（外部端口）：一行委派 application proto 门面；readiness/认证由拦截器承担。 */
@GrpcService
public final class DiscoveryQueryGrpcService extends DiscoveryQueryServiceGrpc.DiscoveryQueryServiceImplBase {

    private final DiscoveryProtoFacade facade;

    public DiscoveryQueryGrpcService(DiscoveryProtoFacade facade) {
        this.facade = facade;
    }

    @Override
    public void query(QueryRequest request, io.grpc.stub.StreamObserver<QueryResponse> responseObserver) {
        responseObserver.onNext(facade.query(request));
        responseObserver.onCompleted();
    }
}
