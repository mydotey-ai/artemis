package org.mydotey.ai.artemis.registry.server;

import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;

/** gRPC bidi 心跳测试设施：单流发送全部请求，返回全部响应（5s 内未全部 ack 即断言失败）。 */
final class Heartbeats {

    private static final long TIMEOUT_SECONDS = 5;

    private Heartbeats() {}

    static List<HeartbeatResponse> collect(Channel channel, HeartbeatRequest... requests) throws InterruptedException {
        CountDownLatch acked = new CountDownLatch(requests.length);
        List<HeartbeatResponse> responses = new CopyOnWriteArrayList<>();
        StreamObserver<HeartbeatRequest> stream = RegistryServiceGrpc.newStub(channel)
                .withInterceptors(AuthClients.authenticated())
                .heartbeat(new StreamObserver<>() {
                    @Override
                    public void onNext(HeartbeatResponse response) {
                        responses.add(response);
                        acked.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {}

                    @Override
                    public void onCompleted() {}
                });
        for (HeartbeatRequest request : requests) {
            stream.onNext(request);
        }
        stream.onCompleted();
        if (!acked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("heartbeat not acked within " + TIMEOUT_SECONDS + "s: " + requests.length);
        }
        return responses;
    }
}
