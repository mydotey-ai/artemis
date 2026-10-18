package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.registry.application.RegistryQueryProtoFacade;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 接入层：内部只读查询的 gRPC 传输绑定（ADR-016）。unary 一行委派 proto 门面；
 * 认证与就绪由既有全局拦截器覆盖，无白名单。
 */
@GrpcService
public final class RegistryQueryGrpcService extends RegistryQueryServiceGrpc.RegistryQueryServiceImplBase {

    private final RegistryQueryProtoFacade facade;

    public RegistryQueryGrpcService(RegistryQueryProtoFacade facade) {
        this.facade = facade;
    }

    @Override
    public void listServices(ListServicesRequest request, StreamObserver<ListServicesResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.listServices(request));
    }

    @Override
    public void getServiceInstances(
            GetServiceInstancesRequest request, StreamObserver<GetServiceInstancesResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.getServiceInstances(request));
    }

    @Override
    public void getLeaseStatus(GetLeaseStatusRequest request, StreamObserver<GetLeaseStatusResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.getLeaseStatus(request));
    }

    @Override
    public void getConfigStatus(
            GetConfigStatusRequest request, StreamObserver<GetConfigStatusResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.getConfigStatus(request));
    }

    private <T> void dispatch(StreamObserver<T> responseObserver, Supplier<T> call) {
        try {
            responseObserver.onNext(call.get());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL
                    .withCause(e)
                    .withDescription("registry query processing failed")
                    .asException());
        }
    }
}
