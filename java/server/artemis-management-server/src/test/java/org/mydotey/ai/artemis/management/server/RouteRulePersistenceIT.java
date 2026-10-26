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
 * 双方言路由规则持久化 IT（v0.5 phase-1）：DB 态 CRUD 落库、审计可查、
 * **跨 management 重启（同 DB 新 context）规则保留**（plan phase-1 验收要点）。
 * 模式同 EjectionPersistenceIT（重启 = 同 DB 新 context，认证源为表账户 seed 默认凭证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
abstract class RouteRulePersistenceIT extends ManagementHttpIT {

    private static final String NAMESPACE = "rr-it";
    private static final String SERVICE = "order-service";
    // 重启用例独立 service：两用例共享容器 DB 且执行顺序不定，键隔离保证断言互不干扰
    private static final String RESTART_SERVICE = "restart-service";
    private static final String RULE = "{\"namespace\":\"%s\",\"service\":\"%s\",\"name\":\"%s\","
            + "\"status\":\"%s\",\"targets\":["
            + "{\"selector\":{\"group\":\"%s\"},\"weight\":%d,\"unreleasedWeight\":%d}]}";

    abstract String jdbcUrl();

    abstract String jdbcUsername();

    abstract String jdbcPassword();

    @Test
    void should_persist_route_rules_and_audit_when_database_mode() throws Exception {
        assertThat(post("/admin/v1/route-rules", rule("alpha", "ACTIVE", "a", 10, 90))
                        .statusCode())
                .isEqualTo(201);
        assertThat(post("/admin/v1/route-rules", rule("beta", "ACTIVE", "b", 20, 80))
                        .statusCode())
                .isEqualTo(201);
        // PUT 全量替换（两段权重与状态均可改）
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(
                                                rule("beta", "INACTIVE", "b", 0, 100))),
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
    void should_restore_route_rules_across_management_restart() throws Exception {
        // 独立规则键（与用例 1 隔离），含兜底目标与两段权重双列
        String body = """
                {"namespace":"%s","service":"%s","name":"keep","targets":[
                {"selector":{"group":"beta"},"weight":30,"unreleasedWeight":70},
                {"selector":{},"weight":70,"unreleasedWeight":30}]}
                """.formatted(NAMESPACE, RESTART_SERVICE);
        assertThat(post("/admin/v1/route-rules", body).statusCode()).isEqualTo(201);

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

            HttpResponse<String> response = send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + restartedPort + "/admin/v1/route-rules/"
                                    + NAMESPACE + "/" + RESTART_SERVICE + "/keep"))
                            .GET(),
                    true);
            JsonNode rule = json(response);
            assertThat(rule.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(rule.get("operator").asText()).isEqualTo("artemis");
            assertThat(rule.get("targets").size()).isEqualTo(2);
            assertThat(rule.get("targets").get(1).get("selector").size()).isZero(); // 兜底目标跨重启保留
            assertThat(rule.get("targets").get(0).get("weight").asInt()).isEqualTo(30);
            assertThat(rule.get("targets").get(0).get("unreleasedWeight").asInt())
                    .isEqualTo(70);
        }
    }

    private static String rule(String name, String status, String group, int weight, int unreleased) {
        return RULE.formatted(NAMESPACE, SERVICE, name, status, group, weight, unreleased);
    }
}
