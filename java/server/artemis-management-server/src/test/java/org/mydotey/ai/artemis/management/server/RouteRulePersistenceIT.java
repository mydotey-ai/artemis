package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * 双方言路由规则持久化 IT（v0.5 phase-1 CRUD + phase-2 发布 + phase-4 canary）：DB 态 CRUD 落库、审计
 * 可查、**跨 management 重启（同 DB 新 context）规则与发布记录保留、重启后回滚仍生效**
 *（plan phase-2 DB 验收要点）。模式同 EjectionPersistenceIT（重启 = 同 DB 新 context）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
abstract class RouteRulePersistenceIT extends ManagementHttpIT {

    private static final String NAMESPACE = "rr-it";
    private static final String SERVICE = "order-service";
    // 重启用例独立 service：两用例共享容器 DB 且执行顺序不定，键隔离保证断言互不干扰
    private static final String RESTART_SERVICE = "restart-service";
    private static final String RULE = "{\"namespace\":\"%s\",\"service\":\"%s\",\"name\":\"%s\","
            + "\"status\":\"%s\",\"targets\":["
            + "{\"selector\":{\"group\":\"%s\"},\"unreleasedWeight\":%d}]}";

    abstract String jdbcUrl();

    abstract String jdbcUsername();

    abstract String jdbcPassword();

    @Test
    void should_persist_route_rules_and_audit_when_database_mode() throws Exception {
        assertThat(post("/admin/v1/route-rules", rule("alpha", "ACTIVE", "a", 90))
                        .statusCode())
                .isEqualTo(201);
        assertThat(post("/admin/v1/route-rules", rule("beta", "ACTIVE", "b", 80))
                        .statusCode())
                .isEqualTo(201);
        // PUT 全量替换（编辑列与状态可改；生效列不在请求体——两段式）
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(rule("beta", "INACTIVE", "b", 100))),
                                true)
                        .statusCode())
                .isEqualTo(200);

        // 列表：(namespace, service, name) 升序；service 过滤
        JsonNode list = json(get("/admin/v1/route-rules?namespace=" + NAMESPACE + "&service=" + SERVICE));
        assertThat(list.get("rules").size()).isEqualTo(2);
        assertThat(list.get("rules").get(0).get("name").asText()).isEqualTo("alpha");
        assertThat(list.get("rules").get(1).get("name").asText()).isEqualTo("beta");
        assertThat(list.get("rules").get(1).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(list.get("rules")
                        .get(1)
                        .get("targets")
                        .get(0)
                        .get("unreleasedWeight")
                        .asInt())
                .isEqualTo(100);

        // 审计可查：operation 过滤 + target 与 operator 透出
        JsonNode audits = json(get("/admin/v1/audit-logs?operation=UPDATE_ROUTE_RULE"));
        assertThat(audits.get("logs").size()).isEqualTo(1);
        assertThat(audits.get("logs").get(0).get("target").asText())
                .isEqualTo("route-rule:" + NAMESPACE + "/" + SERVICE + "/beta");
        assertThat(audits.get("logs").get(0).get("operator").asText()).isEqualTo("artemis");
        JsonNode created = json(get("/admin/v1/audit-logs?operation=CREATE_ROUTE_RULE&limit=10"));
        // 重启用例（键隔离前共享 operation）可能先行，断言包含本用例两条而非精确计数
        assertThat(java.util.stream.StreamSupport.stream(created.get("logs").spliterator(), false)
                        .map(log -> log.get("target").asText()))
                .contains(
                        "route-rule:" + NAMESPACE + "/" + SERVICE + "/alpha",
                        "route-rule:" + NAMESPACE + "/" + SERVICE + "/beta");
    }

    @Test
    void should_keep_releases_and_rollback_across_management_restart() throws Exception {
        // 独立规则键（与用例 1 隔离），含兜底目标；两段式：创建初始生效列 0
        String base = "/admin/v1/route-rules/" + NAMESPACE + "/" + RESTART_SERVICE;
        String body = """
                {"namespace":"%s","service":"%s","name":"keep","targets":[
                {"selector":{"group":"beta"},"unreleasedWeight":30},
                {"selector":{},"unreleasedWeight":70}]}
                """.formatted(NAMESPACE, RESTART_SERVICE);
        assertThat(post("/admin/v1/route-rules", body).statusCode()).isEqualTo(201);
        JsonNode created = json(get(base + "/keep"));
        assertThat(created.get("targets").get(0).get("weight").asInt()).isZero(); // 初始未发布

        // 发布两次：30/70 → 100/0（rollback 源取第一次）
        assertThat(post(base + "/keep/release", "").statusCode()).isEqualTo(200);
        long firstReleaseId = json(get(base + "/keep/releases"))
                .get("releases")
                .get(0)
                .get("id")
                .asLong();
        String secondEdit = """
                {"namespace":"%s","service":"%s","name":"keep","targets":[
                {"selector":{"group":"beta"},"unreleasedWeight":100},
                {"selector":{},"unreleasedWeight":0}]}
                """.formatted(NAMESPACE, RESTART_SERVICE);
        assertThat(send(
                                HttpRequest.newBuilder(uri(base + "/keep"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(secondEdit)),
                                true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(post(base + "/keep/release", "").statusCode()).isEqualTo(200);

        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + jdbcUsername(),
                        "spring.datasource.password=" + jdbcPassword())
                .run()) {
            int restartedPort =
                    ((WebServerApplicationContext) restarted).getWebServer().getPort();
            String restartedBase =
                    "http://localhost:" + restartedPort + "/admin/v1/route-rules/" + NAMESPACE + "/" + RESTART_SERVICE;

            // 规则跨重启保留（发布后生效列）
            JsonNode rule = json(send(
                    HttpRequest.newBuilder(URI.create(restartedBase + "/keep")).GET(), true));
            assertThat(rule.get("targets").get(0).get("weight").asInt()).isEqualTo(100);
            assertThat(rule.get("targets").get(1).get("weight").asInt()).isZero();
            assertThat(rule.get("targets").get(1).get("selector").size()).isZero(); // 兜底目标保留

            // 发布记录跨重启保留（id 降序，最新在前）
            JsonNode releases = json(send(
                    HttpRequest.newBuilder(URI.create(restartedBase + "/keep/releases"))
                            .GET(),
                    true));
            assertThat(releases.get("releases").size()).isEqualTo(2);
            long latestId = releases.get("releases").get(0).get("id").asLong();
            assertThat(latestId).isGreaterThan(firstReleaseId);

            // 重启后按历史 releaseId 回滚仍生效（两列写回源快照）
            HttpResponse<String> rollback = send(
                    HttpRequest.newBuilder(URI.create(restartedBase + "/keep/rollback"))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"releaseId\":" + firstReleaseId + "}")),
                    true);
            assertThat(rollback.statusCode()).isEqualTo(200);
            JsonNode restored = json(rollback);
            assertThat(restored.get("targets").get(0).get("weight").asInt()).isEqualTo(30);
            assertThat(restored.get("targets").get(1).get("weight").asInt()).isEqualTo(70);
            assertThat(restored.get("targets").get(1).get("unreleasedWeight").asInt())
                    .isEqualTo(70); // 两列都写回
        }
    }

    @Test
    void should_keep_canary_rule_and_releases_across_management_restart() throws Exception {
        // canary 预置派生（phase-4）：一键部署落 DB，重启后规则与发布记录保留
        String canaryService = "canary-restart-service";
        assertThat(post(
                                "/admin/v1/canary/" + NAMESPACE + "/" + canaryService,
                                "{\"selector\":{\"version\":\"canary\"},\"weight\":1000}")
                        .statusCode())
                .isEqualTo(200);

        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + jdbcUsername(),
                        "spring.datasource.password=" + jdbcPassword())
                .run()) {
            int restartedPort =
                    ((WebServerApplicationContext) restarted).getWebServer().getPort();
            String restartedBase =
                    "http://localhost:" + restartedPort + "/admin/v1/route-rules/" + NAMESPACE + "/" + canaryService;

            JsonNode rule = json(send(
                    HttpRequest.newBuilder(URI.create(restartedBase + "/canary"))
                            .GET(),
                    true));
            assertThat(rule.get("targets").get(0).get("weight").asInt()).isEqualTo(1000); // 已发布态保留
            assertThat(rule.get("targets").get(1).get("weight").asInt()).isEqualTo(9000); // 兜底保留

            JsonNode releases = json(send(
                    HttpRequest.newBuilder(URI.create(restartedBase + "/canary/releases"))
                            .GET(),
                    true));
            assertThat(releases.get("releases").size()).isEqualTo(1); // deploy 的 RELEASE 记录保留
        }
    }

    private static String rule(String name, String status, String group, int unreleased) {
        return RULE.formatted(NAMESPACE, SERVICE, name, status, group, unreleased);
    }
}
