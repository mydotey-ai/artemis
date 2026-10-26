package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 双方言摘除持久化 IT（v0.4 phase-3）：DB 态四级摘除/恢复/对消经统一 HTTP API 落库、
 * 审计可查（含改密）、**跨 management 重启（同 DB 新 context）摘除经规则流快照恢复**
 * （roadmap v0.4 验收要点）。改密不可逆 → @Order 定序（AccountFlowIT 先例），
 * 改密用例最后。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "artemis.management.grpc.port=0"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class EjectionPersistenceIT {

    private static final String PASSWORD = "artemis";
    private static final String AUTH = BasicAuth.headerValue("artemis", PASSWORD);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    abstract String jdbcUrl();

    abstract String jdbcUsername();

    abstract String jdbcPassword();

    @Test
    @Order(1)
    void should_persist_four_scopes_and_audit_when_database_mode() throws Exception {
        assertThat(post("/admin/v1/ejections", instanceBody("ns-p", "svc-a", "i1", "maintenance"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/admin/v1/ejections", selectorBody("SERVER", "10.1.1.1", "host down"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(post("/admin/v1/ejections/recover", selectorBody("SERVER", "10.1.1.1", null))
                        .statusCode())
                .isEqualTo(204);

        // active 集只含未对消目标；全量含 RECOVER 反向记录
        JsonNode active = json(get("/admin/v1/ejections?activeOnly=true"));
        assertThat(active.get("records").size()).isEqualTo(1);
        assertThat(active.get("records").get(0).get("scope").asText()).isEqualTo("INSTANCE");
        JsonNode all = json(get("/admin/v1/ejections"));
        assertThat(all.get("records").size()).isEqualTo(3);

        // 审计可查：摘除/恢复摘要 + operation 过滤 + operator 透出
        JsonNode audits = json(get("/admin/v1/audit-logs?operation=RECOVER"));
        assertThat(audits.get("logs").size()).isEqualTo(1);
        assertThat(audits.get("logs").get(0).get("target").asText()).isEqualTo("server:10.1.1.1");
        JsonNode allAudits = json(get("/admin/v1/audit-logs"));
        assertThat(allAudits.get("logs").size()).isEqualTo(3); // 2 EJECT + 1 RECOVER，倒序首条最新
        assertThat(allAudits.get("logs").get(0).get("operator").asText()).isEqualTo("artemis");
    }

    @Test
    @Order(2)
    void should_restore_ejections_across_management_restart() throws Exception {
        // 独立目标键（与用例 1 隔离）：instance 级 + selector 级
        post("/admin/v1/ejections", instanceBody("ns-r", "svc-r", "i1", "restart keep"));
        post("/admin/v1/ejections", selectorBody("ZONE", "dc-restart", "zone down"));

        // 重启语义：同 DB 新 context（AccountFlowIT 模式）；表非空不 re-seed，认证源为表账户
        // （此时密码仍为 seed 默认值，改密在用例 3）
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "artemis.management.grpc.port=0",
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + jdbcUsername(),
                        "spring.datasource.password=" + jdbcPassword(),
                        "artemis.management.auth.username=changed-admin",
                        "artemis.management.auth.password=changed-pass")
                .run()) {
            WebServerApplicationContext web = (WebServerApplicationContext) restarted;
            int restartedPort = web.getWebServer().getPort();
            int grpcPort = restarted.getBeansOfType(GrpcServerLifecycle.class).values().stream()
                    .findFirst()
                    .orElseThrow()
                    .getPort();

            // 重启后记录仍可查（DB 态持久）+ 配置凭证失效（表非空不 re-seed）
            JsonNode active = json(get(restartedPort, "/admin/v1/ejections?activeOnly=true&scope=ZONE"));
            assertThat(active.get("records").size()).isEqualTo(1);
            assertThat(active.get("records").get(0).get("value").asText()).isEqualTo("dc-restart");
            assertThat(getUnauthorized(restartedPort, "/admin/v1/ejections").statusCode())
                    .isEqualTo(401);

            // 规则流快照恢复（roadmap v0.4 验收要点）：新订阅收含四级摘除的快照
            EjectionRulesEvent snapshot = watchSnapshot(grpcPort);
            // 快照携带版本（phase-5）：DB sequence 持久，跨重启派生不归零
            assertThat(snapshot.getVersion()).isPositive();
            assertThat(snapshot.hasSnapshot()).isTrue();
            assertThat(snapshot.getSnapshot().getRulesList().stream()
                            .anyMatch(rule -> rule.getTargetValue().equals("dc-restart")))
                    .isTrue();
            assertThat(snapshot.getSnapshot().getRulesList().stream()
                            .anyMatch(rule -> "i1".equals(rule.getInstanceId())
                                    && "ns-r".equals(rule.getService().getNamespace())))
                    .isTrue();
        }
    }

    @Test
    @Order(3)
    void should_audit_password_change_in_database_mode() throws Exception {
        HttpRequest change = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + httpPort + "/admin/v1/account/password"))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"oldPassword\":\"artemis\",\"newPassword\":\"eject-it-pass-9\"}"))
                .build();
        assertThat(HTTP.send(change, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);

        // 改密即时生效：查询用新密码
        HttpRequest query = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + httpPort + "/admin/v1/audit-logs?operation=CHANGE_PASSWORD&limit=5"))
                .header("Authorization", BasicAuth.headerValue("artemis", "eject-it-pass-9"))
                .GET()
                .build();
        JsonNode audits = json(HTTP.send(query, HttpResponse.BodyHandlers.ofString()));
        assertThat(audits.get("logs").size()).isEqualTo(1);
        assertThat(audits.get("logs").get(0).get("target").asText()).isEqualTo("account:artemis");
    }

    // ---- 设施 ----

    private static String instanceBody(String namespace, String service, String instanceId, String reason) {
        StringBuilder body = new StringBuilder("{\"scope\":\"INSTANCE\",\"namespace\":\"")
                .append(namespace)
                .append("\",\"service\":\"")
                .append(service)
                .append("\",\"instanceId\":\"")
                .append(instanceId)
                .append("\"");
        if (reason != null) {
            body.append(",\"reason\":\"").append(reason).append("\"");
        }
        return body.append("}").toString();
    }

    private static String selectorBody(String scope, String value, String reason) {
        StringBuilder body = new StringBuilder("{\"scope\":\"")
                .append(scope)
                .append("\",\"value\":\"")
                .append(value)
                .append("\"");
        if (reason != null) {
            body.append(",\"reason\":\"").append(reason).append("\"");
        }
        return body.append("}").toString();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return post(httpPort, path, body);
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(httpPort, path);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, path))
                .header("Authorization", AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getUnauthorized(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, path))
                .header("Authorization", BasicAuth.headerValue("changed-admin", "changed-pass"))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    /** 重启后 context 的规则流首个事件（必为快照，D-E7）。 */
    private static EjectionRulesEvent watchSnapshot(int grpcPort) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        try {
            BlockingQueue<EjectionRulesEvent> queue = new LinkedBlockingQueue<>();
            io.grpc.stub.ClientCalls.asyncServerStreamingCall(
                    channel.newCall(EjectionRuleServiceGrpc.getWatchEjectionsMethod(), io.grpc.CallOptions.DEFAULT),
                    EjectionRulesRequest.newBuilder()
                            .setSubscriberId("ejection-restart-it")
                            .build(),
                    new io.grpc.stub.StreamObserver<>() {
                        @Override
                        public void onNext(EjectionRulesEvent event) {
                            queue.offer(event);
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    });
            EjectionRulesEvent event = queue.poll(10, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            return event;
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
