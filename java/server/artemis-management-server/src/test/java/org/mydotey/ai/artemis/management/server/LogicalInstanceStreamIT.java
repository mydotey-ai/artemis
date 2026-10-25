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
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstanceServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 服务级集成测试（v0.4 phase-4）：真实 management-server gRPC 逻辑实例流端口（随机端口）——
 * 连接收快照、upsert/delete 增量、断流重连快照收敛、identical no-op 零推送、客户端取消注销。
 * registry 上游不触达（逻辑实例流与 registry 查询独立）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "artemis.management.grpc.port=0"})
class LogicalInstanceStreamIT {

    private static final String NAMESPACE = "logical-it";
    private static final String SERVICE = "third-party-service";
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
        // 隔离：清掉上一用例逻辑实例（幂等删除）
        delete("l1");
        delete("l2");
        delete("l9");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_deliver_snapshot_then_increments_over_stream() throws Exception {
        upsert("l1", "10.0.0.1");
        EventQueue events = watch();
        LogicalInstancesEvent snapshot = events.take(1).get(0);

        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getInstancesCount()).isEqualTo(1);
        assertThat(snapshot.getSnapshot().getInstances(0).getInstance().getInstanceId())
                .isEqualTo("l1");
        assertThat(snapshot.getSnapshot().getInstances(0).getInstance().getHost())
                .isEqualTo("10.0.0.1");

        upsert("l2", "10.0.0.2");
        LogicalInstancesEvent upserted = events.take(1).get(0);
        assertThat(upserted.hasUpserted()).isTrue();
        assertThat(upserted.getUpserted().getInstance().getInstanceId()).isEqualTo("l2");

        delete("l1");
        LogicalInstancesEvent deleted = events.take(1).get(0);
        assertThat(deleted.hasDeleted()).isTrue();
        assertThat(deleted.getDeleted().getInstanceId()).isEqualTo("l1");
    }

    @Test
    void should_reconnect_and_resnapshot_after_stream_break() throws Exception {
        upsert("l1", "10.0.0.1");
        EventQueue events = watch();
        events.take(1); // 快照

        // 断流期间变更（事件丢失 = 断流窗口）
        events.cancel();
        upsert("l2", "10.0.0.2");

        // 重连收全量快照收敛（含断流期间的 l2）
        EventQueue reconnected = watch();
        LogicalInstancesEvent snapshot = reconnected.take(1).get(0);
        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getInstancesList())
                .extracting(instance -> instance.getInstance().getInstanceId())
                .containsExactlyInAnyOrder("l1", "l2");
    }

    @Test
    void should_not_push_when_upsert_identical_content() throws Exception {
        upsert("l1", "10.0.0.1");
        EventQueue events = watch();
        events.take(1); // 快照

        upsert("l1", "10.0.0.1"); // identical no-op
        assertThat(events.takeQuietly(300, TimeUnit.MILLISECONDS)).isEmpty();
    }

    @Test
    void should_send_empty_snapshot_when_no_logical_instances() throws Exception {
        EventQueue events = watch();

        LogicalInstancesEvent snapshot = events.take(1).get(0);

        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getInstancesCount()).isZero();
    }

    @Test
    void should_unregister_on_client_cancel_and_keep_stream_healthy() throws Exception {
        EventQueue cancelled = watch();
        cancelled.take(1); // 快照
        cancelled.cancel(); // 客户端取消

        EventQueue healthy = watch();
        healthy.take(1); // 快照

        upsert("l9", "10.0.0.9");
        assertThat(healthy.take(1).get(0).getUpserted().getInstance().getInstanceId())
                .isEqualTo("l9");
        assertThat(cancelled.takeQuietly(300, TimeUnit.MILLISECONDS)).isEmpty();
    }

    // ---- 设施 ----

    private EventQueue watch() {
        EventQueue events = new EventQueue();
        io.grpc.ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> call = channel.newCall(
                LogicalInstanceServiceGrpc.getWatchLogicalInstancesMethod(), io.grpc.CallOptions.DEFAULT);
        events.attach(call);
        io.grpc.stub.ClientCalls.asyncServerStreamingCall(
                call,
                LogicalInstancesRequest.newBuilder()
                        .setSubscriberId("logical-instance-it")
                        .build(),
                events);
        return events;
    }

    private void upsert(String instanceId, String host) {
        send(
                "PUT",
                "/admin/v1/logical-instances",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE + "\",\"instanceId\":\"" + instanceId
                        + "\",\"host\":\"" + host + "\",\"port\":8080,\"metadata\":{\"protocol\":\"http\"}}");
    }

    private void delete(String instanceId) {
        send(
                "DELETE",
                "/admin/v1/logical-instances?namespace=" + NAMESPACE + "&service=" + SERVICE + "&instanceId="
                        + instanceId,
                "");
    }

    private void send(String method, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + path))
                    .header("Authorization", AUTH)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        method + " " + path + " -> " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 事件收集器：gRPC 回调线程入队，测试线程限时拉取；持 call 供取消。 */
    private static final class EventQueue implements StreamObserver<LogicalInstancesEvent> {

        private final BlockingQueue<LogicalInstancesEvent> queue = new LinkedBlockingQueue<>();
        private volatile io.grpc.ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> call;

        void attach(io.grpc.ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> call) {
            this.call = call;
        }

        void cancel() {
            io.grpc.ClientCall<LogicalInstancesRequest, LogicalInstancesEvent> current = call;
            if (current != null) {
                current.cancel("test cancel", null);
            }
        }

        @Override
        public void onNext(LogicalInstancesEvent event) {
            queue.offer(event);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}

        List<LogicalInstancesEvent> take(int count) throws InterruptedException {
            List<LogicalInstancesEvent> events = new java.util.ArrayList<>(count);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (events.size() < count) {
                LogicalInstancesEvent event =
                        queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) {
                    throw new AssertionError("timeout waiting for " + count + " events, got " + events.size());
                }
                events.add(event);
            }
            return events;
        }

        List<LogicalInstancesEvent> takeQuietly(long timeout, TimeUnit unit) throws InterruptedException {
            List<LogicalInstancesEvent> drained = new java.util.ArrayList<>();
            queue.drainTo(drained);
            if (drained.isEmpty()) {
                LogicalInstancesEvent late = queue.poll(timeout, unit);
                if (late != null) {
                    drained.add(late);
                }
            }
            return drained;
        }
    }
}
