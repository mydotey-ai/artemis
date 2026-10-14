package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse;
import org.mydotey.ai.artemis.registry.application.RegistryProtoFacade;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 接入层：registry 四 rpc 的 gRPC 传输绑定。unary 一行委派 application 的 proto 门面；
 * 心跳 bidi 流逐请求回 ack（seq 使 ack 可归属）——流断开 = 停止续约，租约自然过期（架构 §4.1）。
 * 委派链意外异常不逃逸出 StreamObserver：unary 以单请求粒度经 {@code onError(INTERNAL)} 隔离；
 * 心跳流的意外异常以流错误收尾（设计接受：断流 → TTL 倒计时，客户端重连全量上报自愈）。
 */
@GrpcService
public final class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final RegistryProtoFacade facade;

    public RegistryGrpcService(RegistryProtoFacade facade) {
        this.facade = facade;
    }

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.register(request));
    }

    @Override
    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.deregister(request));
    }

    @Override
    public void update(UpdateRequest request, StreamObserver<UpdateResponse> responseObserver) {
        dispatch(responseObserver, () -> facade.update(request));
    }

    @Override
    public StreamObserver<HeartbeatRequest> heartbeat(StreamObserver<HeartbeatResponse> responseObserver) {
        return new StreamObserver<>() {
            private boolean dead;

            @Override
            public void onNext(HeartbeatRequest request) {
                if (dead) {
                    return; // 流已因前序请求故障/客户端取消终止：不再处理已缓冲消息（变更无可投递的 ack）
                }
                HeartbeatResponse response;
                try {
                    response = facade.heartbeat(request);
                } catch (RuntimeException e) {
                    dead = true;
                    // 单请求意外异常以流错误收尾：门面已把可预期失败映射为 FAIL ack
                    responseObserver.onError(internalError(e));
                    return;
                }
                try {
                    responseObserver.onNext(response);
                } catch (RuntimeException e) {
                    // 客户端并发取消/断流：ack 不可投递视为流终止，不再二次 onError（防 closed call 二次异常）
                    dead = true;
                }
            }

            @Override
            public void onError(Throwable error) {
                dead = true;
                // 客户端断流：无服务端清理动作，租约自最后一次续约起算 TTL 自然过期
            }

            @Override
            public void onCompleted() {
                if (!dead) {
                    responseObserver.onCompleted();
                }
            }
        };
    }

    private <T> void dispatch(StreamObserver<T> responseObserver, Supplier<T> call) {
        try {
            responseObserver.onNext(call.get());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(internalError(e));
        }
    }

    private static Exception internalError(RuntimeException cause) {
        return Status.INTERNAL
                .withCause(cause)
                .withDescription("registry request processing failed")
                .asException();
    }
}
