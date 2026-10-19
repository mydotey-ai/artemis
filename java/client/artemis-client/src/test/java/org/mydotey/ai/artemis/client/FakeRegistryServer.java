package org.mydotey.ai.artemis.client;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse;

/**
 * 进程内可编程假 registry 服务：记录全部请求与认证头，默认成功 ack、心跳 echo seq；
 * 支持服务端主动断流与拒绝模式（UNAVAILABLE）。
 */
public final class FakeRegistryServer {

    public final List<RegisterRequest> registerRequests = new CopyOnWriteArrayList<RegisterRequest>();
    public final List<UpdateRequest> updateRequests = new CopyOnWriteArrayList<UpdateRequest>();
    public final List<DeregisterRequest> deregisterRequests = new CopyOnWriteArrayList<DeregisterRequest>();
    public final List<HeartbeatRequest> heartbeatRequests = new CopyOnWriteArrayList<HeartbeatRequest>();
    public final List<String> authorizationHeaders = new CopyOnWriteArrayList<String>();

    public volatile boolean rejectAll;
    public volatile boolean failHeartbeatEcho;

    private volatile StreamObserver<HeartbeatResponse> heartbeatResponder;
    private Server server;

    public void start() throws IOException {
        server = ServerBuilder.forPort(0)
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                        String authorization =
                                headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
                        if (authorization != null) {
                            authorizationHeaders.add(authorization);
                        }
                        return next.startCall(call, headers);
                    }
                })
                .addService(new RegistryServiceGrpc.RegistryServiceImplBase() {
                    @Override
                    public void register(RegisterRequest request, StreamObserver<RegisterResponse> observer) {
                        registerRequests.add(request);
                        if (rejectAll) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("fake reject")
                                    .asException());
                            return;
                        }
                        observer.onNext(RegisterResponse.getDefaultInstance());
                        observer.onCompleted();
                    }

                    @Override
                    public void update(UpdateRequest request, StreamObserver<UpdateResponse> observer) {
                        updateRequests.add(request);
                        if (rejectAll) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("fake reject")
                                    .asException());
                            return;
                        }
                        observer.onNext(UpdateResponse.getDefaultInstance());
                        observer.onCompleted();
                    }

                    @Override
                    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> observer) {
                        deregisterRequests.add(request);
                        if (rejectAll) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("fake reject")
                                    .asException());
                            return;
                        }
                        observer.onNext(DeregisterResponse.getDefaultInstance());
                        observer.onCompleted();
                    }

                    @Override
                    public StreamObserver<HeartbeatRequest> heartbeat(
                            final StreamObserver<HeartbeatResponse> observer) {
                        heartbeatResponder = observer;
                        StreamObserver<HeartbeatRequest> inbound = new StreamObserver<HeartbeatRequest>() {
                            @Override
                            public void onNext(HeartbeatRequest request) {
                                heartbeatRequests.add(request);
                                if (failHeartbeatEcho) {
                                    return; // 收而不回：模拟 ack 丢失
                                }
                                observer.onNext(HeartbeatResponse.newBuilder()
                                        .setStatus(successStatus())
                                        .setData(
                                                org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponseData
                                                        .newBuilder()
                                                        .setServerTimeMs(System.currentTimeMillis())
                                                        .setSeq(request.getSeq()))
                                        .build());
                            }

                            @Override
                            public void onError(Throwable error) {}

                            @Override
                            public void onCompleted() {
                                observer.onCompleted();
                            }
                        };
                        return inbound;
                    }
                })
                .build()
                .start();
    }

    public int port() {
        return server.getPort();
    }

    public String address() {
        return "localhost:" + port();
    }

    /** 服务端主动断心跳流（响应流 onError——客户端才会感知断连并重连）。 */
    public void breakHeartbeatStream() {
        StreamObserver<HeartbeatResponse> responder = heartbeatResponder;
        if (responder != null) {
            responder.onError(Status.UNAVAILABLE.withDescription("fake break").asException());
        }
    }

    public void stop() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    static ResponseStatus successStatus() {
        return ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build();
    }
}
