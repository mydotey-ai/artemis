package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.management.v1.RouteRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 服务级集成测试（v0.5 phase-2）：真实 management-server 路由规则流端口——
 * 连接收快照（ACTIVE 集）、release/rollback 推 upserted、INACTIVE 化推 deleted、
 * ack 水位登记。registry 上游不触达（规则流与 registry 查询独立）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "artemis.management.grpc.port=0"})
class RouteRuleStreamIT {

    private static final String NAMESPACE = "rr-stream-it";
    private static final String SERVICE = "order-service";
    private static final String BASE = "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE;
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

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
        // 隔离：清掉上一用例规则（删除幂等）
        deleteRule("beta");
        deleteRule("inactive");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_send_active_rules_snapshot_on_connect() throws Exception {
        createRule("beta", "ACTIVE", 20);
        createRule("inactive", "INACTIVE", 10);

        EventQueue events = watch();
        RouteRulesEvent snapshot = events.take(1).get(0);

        assertThat(snapshot.hasSnapshot()).isTrue();
        assertThat(snapshot.getSnapshot().getRulesCount()).isEqualTo(1); // 仅 ACTIVE
        assertThat(snapshot.getSnapshot().getRules(0).getName()).isEqualTo("beta");
        assertThat(snapshot.getSnapshot().getRules(0).getTargets(0).getUnreleasedWeight())
                .isEqualTo(20);
        assertThat(snapshot.getSnapshot().getRules(0).getTargets(0).getWeight()).isZero();
    }

    @Test
    void should_push_upserted_on_release_and_deleted_on_inactive() throws Exception {
        createRule("beta", "ACTIVE", 20);
        EventQueue events = watch();
        events.take(1); // 快照

        // release → upserted（编辑列拷贝生效列）
        post(BASE + "/beta/release", "");
        RouteRulesEvent released = events.take(1).get(0);
        assertThat(released.hasUpserted()).isTrue();
        assertThat(released.getUpserted().getName()).isEqualTo("beta");
        assertThat(released.getUpserted().getTargets(0).getWeight()).isEqualTo(20);
        assertThat(released.getVersion()).isPositive();

        // rollback → upserted（恢复历史）
        long releaseId = releases("beta").get(0);
        post(BASE + "/beta/rollback", "{\"releaseId\":" + releaseId + "}");
        RouteRulesEvent rolledBack = events.take(1).get(0);
        assertThat(rolledBack.hasUpserted()).isTrue();
        assertThat(rolledBack.getUpserted().getTargets(0).getWeight()).isEqualTo(20);
        assertThat(rolledBack.getVersion()).isGreaterThan(released.getVersion());

        // INACTIVE 化（PUT 改状态）→ deleted（离开 ACTIVE 集）
        post("/admin/v1/route-rules", ruleBody("beta", "ACTIVE", 0));
        sendPut(BASE + "/beta", ruleBody("beta", "INACTIVE", 0));
        RouteRulesEvent deleted = events.take(1).get(0);
        assertThat(deleted.hasDeleted()).isTrue();
        assertThat(deleted.getDeleted().getName()).isEqualTo("beta");
        assertThat(deleted.getDeleted().getService().getService()).isEqualTo(SERVICE);
    }

    @Test
    void should_track_route_rule_watermark_when_acked() throws Exception {
        EventQueue events = watch("rr-watermark-it");
        RouteRulesEvent snapshot = events.take(1).get(0);
        long snapshotVersion = snapshot.getVersion();

        // ack 已应用版本 → routeRules 水位节可见（独立版本域，不干扰摘除流字段）
        ack("rr-watermark-it", snapshotVersion);
        var status = ruleStatus();
        assertThat(status.get("routeRules")
                        .getAsJsonObject()
                        .get("currentVersion")
                        .getAsLong())
                .isEqualTo(snapshotVersion);
        assertThat(status.get("routeRules")
                        .getAsJsonObject()
                        .get("appliedLatestCount")
                        .getAsInt())
                .isEqualTo(1);

        // release → 版本推进；ack 前未生效，ack 后生效
        createRule("beta", "ACTIVE", 50);
        RouteRulesEvent upserted = events.take(1).get(0);
        long nextVersion = upserted.getVersion();
        assertThat(nextVersion).isGreaterThan(snapshotVersion);
        assertThat(ruleStatus()
                        .get("routeRules")
                        .getAsJsonObject()
                        .get("appliedLatestCount")
                        .getAsInt())
                .isZero();
        ack("rr-watermark-it", nextVersion);
        assertThat(ruleStatus()
                        .get("routeRules")
                        .getAsJsonObject()
                        .get("appliedLatestCount")
                        .getAsInt())
                .isEqualTo(1);
    }

    // ---- 设施 ----

    private EventQueue watch() {
        return watch("route-rule-it");
    }

    private EventQueue watch(String subscriberId) {
        EventQueue events = new EventQueue();
        io.grpc.ClientCall<RouteRulesRequest, RouteRulesEvent> call =
                channel.newCall(RouteRuleServiceGrpc.getWatchRouteRulesMethod(), io.grpc.CallOptions.DEFAULT);
        events.attach(call);
        io.grpc.stub.ClientCalls.asyncServerStreamingCall(
                call,
                RouteRulesRequest.newBuilder().setSubscriberId(subscriberId).build(),
                events);
        return events;
    }

    private void ack(String subscriberId, long appliedVersion) {
        RouteRuleServiceGrpc.newBlockingStub(channel)
                .ackRouteRules(org.mydotey.ai.artemis.proto.management.v1.AckRouteRulesRequest.newBuilder()
                        .setSubscriberId(subscriberId)
                        .setAppliedVersion(appliedVersion)
                        .build());
    }

    /** GET /admin/v1/status/rules（routeRules 水位节；gson 解析）。 */
    private com.google.gson.JsonObject ruleStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/admin/v1/status/rules"))
                    .header("Authorization", AUTH)
                    .GET()
                    .build();
            return com.google.gson.JsonParser.parseString(HTTP.send(request, HttpResponse.BodyHandlers.ofString())
                            .body())
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ruleBody(String name, String status, int unreleased) {
        return "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE + "\",\"name\":\"" + name
                + "\",\"status\":\"" + status
                + "\",\"targets\":[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":"
                + unreleased + "}]}";
    }

    private void createRule(String name, String status, int unreleased) throws Exception {
        assertThat(post("/admin/v1/route-rules", ruleBody(name, status, unreleased))
                        .statusCode())
                .isEqualTo(201);
    }

    private java.util.List<Long> releases(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + BASE + "/" + name + "/releases"))
                    .header("Authorization", AUTH)
                    .GET()
                    .build();
            var array = com.google.gson.JsonParser.parseString(HTTP.send(request, HttpResponse.BodyHandlers.ofString())
                            .body())
                    .getAsJsonObject()
                    .get("releases")
                    .getAsJsonArray();
            return java.util.stream.IntStream.range(0, array.size())
                    .mapToObj(i -> array.get(i).getAsJsonObject().get("id").getAsLong())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteRule(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + BASE + "/" + name))
                    .header("Authorization", AUTH)
                    .DELETE()
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void sendPut(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 事件收集器：gRPC 回调线程入队，测试线程限时拉取；持 call 供取消。 */
    private static final class EventQueue implements StreamObserver<RouteRulesEvent> {

        private final BlockingQueue<RouteRulesEvent> queue = new LinkedBlockingQueue<>();
        private volatile io.grpc.ClientCall<RouteRulesRequest, RouteRulesEvent> call;

        void attach(io.grpc.ClientCall<RouteRulesRequest, RouteRulesEvent> call) {
            this.call = call;
        }

        java.util.List<RouteRulesEvent> take(int count) throws InterruptedException {
            java.util.List<RouteRulesEvent> events = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                RouteRulesEvent event = queue.poll(5, TimeUnit.SECONDS);
                if (event == null) {
                    throw new AssertionError("event not received within timeout");
                }
                events.add(event);
            }
            return events;
        }

        java.util.List<RouteRulesEvent> takeQuietly(long timeout, TimeUnit unit) throws InterruptedException {
            java.util.List<RouteRulesEvent> events = new java.util.ArrayList<>();
            RouteRulesEvent event;
            while ((event = queue.poll(timeout, unit)) != null) {
                events.add(event);
            }
            return events;
        }

        @Override
        public void onNext(RouteRulesEvent event) {
            queue.offer(event);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
