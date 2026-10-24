package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 服务级集成测试（phase-6）：真实 management-server gRPC 规则流端口（随机端口）——
 * 连接收快照、eject/recover 增量、客户端取消注销。registry 上游不触达（规则流与
 * registry 查询独立）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "artemis.management.grpc.port=0"})
class EjectionRuleStreamIT {

    private static final String NAMESPACE = "rule-it";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private org.springframework.context.ConfigurableApplicationContext applicationContext;

    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        int grpcPort = applicationContext.getBeansOfType(GrpcServerLifecycle.class).values().stream()
                .findFirst()
                .orElseThrow()
                .getPort();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        // 隔离：清掉上一用例摘除记录
        recover("i1");
        recover("i2");
        recover("i9");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_send_snapshot_on_connect() throws Exception {
        eject("i1", "maintenance");
        eject("i2", "draining");

        EventQueue events = watch();
        EjectionRulesEvent snapshot = events.take(1).get(0);

        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getRulesCount()).isEqualTo(2);
        assertThat(snapshot.getSnapshot().getRulesList())
                .extracting(
                        rule -> rule.getService().getNamespace(),
                        rule -> rule.getService().getService(),
                        rule -> rule.getInstanceId(),
                        rule -> rule.getReason())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NAMESPACE, SERVICE, "i1", "maintenance"),
                        org.assertj.core.groups.Tuple.tuple(NAMESPACE, SERVICE, "i2", "draining"));
    }

    @Test
    void should_send_empty_snapshot_when_no_ejections() throws Exception {
        EventQueue events = watch();

        EjectionRulesEvent snapshot = events.take(1).get(0);

        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getRulesCount()).isZero();
    }

    @Test
    void should_push_delta_on_eject_and_recover() throws Exception {
        EventQueue events = watch();
        events.take(1); // 快照

        eject("i1", "maintenance");
        EjectionRulesEvent ejected = events.take(1).get(0);
        assertThat(ejected.hasEjected()).isTrue();
        assertThat(ejected.getEjected().getInstanceId()).isEqualTo("i1");

        recover("i1");
        EjectionRulesEvent recovered = events.take(1).get(0);
        assertThat(recovered.hasRecovered()).isTrue();
        assertThat(recovered.getRecovered().getInstanceId()).isEqualTo("i1");
    }

    @Test
    void should_unregister_on_client_cancel_and_keep_stream_healthy() throws Exception {
        EventQueue cancelled = watch();
        cancelled.take(1); // 快照
        cancelled.cancel(); // 客户端取消

        EventQueue healthy = watch();
        healthy.take(1); // 快照

        // 取消后摘除：旧流不再收（注销），新流继续收增量
        eject("i1", "maintenance");
        assertThat(healthy.take(1).get(0).getEjected().getInstanceId()).isEqualTo("i1");
        assertThat(cancelled.takeQuietly(300, TimeUnit.MILLISECONDS)).isEmpty();
    }

    // ---- 设施 ----

    private EventQueue watch() {
        EventQueue events = new EventQueue();
        io.grpc.ClientCall<EjectionRulesRequest, EjectionRulesEvent> call =
                channel.newCall(EjectionRuleServiceGrpc.getWatchEjectionsMethod(), io.grpc.CallOptions.DEFAULT);
        events.attach(call);
        io.grpc.stub.ClientCalls.asyncServerStreamingCall(
                call,
                EjectionRulesRequest.newBuilder()
                        .setSubscriberId("ejection-rule-it")
                        .build(),
                events);
        return events;
    }

    private void eject(String instanceId, String reason) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + "/admin/v1/services/" + NAMESPACE + "/" + SERVICE
                        + "/instances/" + instanceId + "/eject"))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"" + reason + "\"}"))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void recover(String instanceId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/admin/v1/services/" + NAMESPACE + "/" + SERVICE
                            + "/instances/" + instanceId + "/recover"))
                    .header("Authorization", AUTH)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 事件收集器：gRPC 回调线程入队，测试线程限时拉取；持 call 供取消。 */
    private static final class EventQueue implements StreamObserver<EjectionRulesEvent> {

        private final BlockingQueue<EjectionRulesEvent> queue = new LinkedBlockingQueue<>();
        private volatile io.grpc.ClientCall<EjectionRulesRequest, EjectionRulesEvent> call;

        void attach(io.grpc.ClientCall<EjectionRulesRequest, EjectionRulesEvent> call) {
            this.call = call;
        }

        void cancel() {
            io.grpc.ClientCall<EjectionRulesRequest, EjectionRulesEvent> current = call;
            if (current != null) {
                current.cancel("test cancel", null);
            }
        }

        @Override
        public void onNext(EjectionRulesEvent event) {
            queue.offer(event);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}

        List<EjectionRulesEvent> take(int count) throws InterruptedException {
            List<EjectionRulesEvent> events = new java.util.ArrayList<>(count);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (events.size() < count) {
                EjectionRulesEvent event = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) {
                    throw new AssertionError("timeout waiting for " + count + " events, got " + events.size());
                }
                events.add(event);
            }
            return events;
        }

        List<EjectionRulesEvent> takeQuietly(long timeout, TimeUnit unit) throws InterruptedException {
            List<EjectionRulesEvent> drained = new java.util.ArrayList<>();
            queue.drainTo(drained);
            if (drained.isEmpty()) {
                EjectionRulesEvent late = queue.poll(timeout, unit);
                if (late != null) {
                    drained.add(late);
                }
            }
            return drained;
        }
    }
}
