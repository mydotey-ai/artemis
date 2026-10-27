package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * canary 一键用法 HTTP 契约 IT（v0.5 phase-4，内存态）：deploy / delete 两端点 +
 * 派生规则视图（两列一致）+ 发布记录（既有 rollback 对 canary 规则可用）+ 保留名
 * 拒绝 + 校验 400。方法级键隔离（共享 context 内存 store，phase-1 先例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class CanaryIT extends ManagementHttpIT {

    private static final String NAMESPACE = "canary-it";

    /** 每方法独立 service：canary 规则键 = (namespace, service, canary)，防跨方法累计。 */
    private static String canaryPath(String service) {
        return "/admin/v1/canary/" + NAMESPACE + "/" + service;
    }

    private static String rulePath(String service) {
        return "/admin/v1/route-rules/" + NAMESPACE + "/" + service + "/canary";
    }

    private static final String DEPLOY_BODY = "{\"selector\":{\"version\":\"canary\"},\"weight\":%d}";

    @Test
    void should_deploy_canary_over_http() throws Exception {
        JsonNode deployed = json(post(canaryPath("deploy-service"), DEPLOY_BODY.formatted(1000)));

        // 派生规则：保留名 canary、canary 目标 + 兜底目标、两列一致（已发布态）
        assertThat(deployed.get("name").asText()).isEqualTo("canary");
        assertThat(deployed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(deployed.get("targets").size()).isEqualTo(2);
        assertThat(deployed.get("targets").get(0).get("selector").get("version").asText())
                .isEqualTo("canary");
        assertThat(deployed.get("targets").get(0).get("weight").asInt()).isEqualTo(1000);
        assertThat(deployed.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(1000);
        assertThat(deployed.get("targets").get(1).get("selector").size()).isZero();
        assertThat(deployed.get("targets").get(1).get("weight").asInt()).isEqualTo(9000);

        // 规则即普通 route-rule：既有查询/发布记录 API 直接可用
        String rule = rulePath("deploy-service");
        assertThat(json(get(rule)).get("name").asText()).isEqualTo("canary");
        JsonNode releases = json(get(rule + "/releases"));
        assertThat(releases.get("releases").size()).isEqualTo(1);
        assertThat(releases.get("releases").get(0).get("type").asText()).isEqualTo("RELEASE");
        assertThat(json(get("/admin/v1/audit-logs?operation=DEPLOY_CANARY"))
                        .get("logs")
                        .get(0)
                        .get("target")
                        .asText())
                .isEqualTo("route-rule:" + NAMESPACE + "/deploy-service/canary");
    }

    @Test
    void should_return_400_when_canary_request_invalid() throws Exception {
        String canary = canaryPath("invalid-service");
        assertThat(post(canary, "{\"selector\":{},\"weight\":1000}").statusCode())
                .isEqualTo(400);
        assertThat(post(canary, "{\"weight\":1000}").statusCode()).isEqualTo(400);
        assertThat(post(canary, "{\"selector\":{\"version\":\"canary\"}}").statusCode())
                .isEqualTo(400); // weight 缺失
        assertThat(post(canary, "{\"selector\":{\"version\":\"canary\"},\"weight\":10001}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(post(canary, "").statusCode()).isEqualTo(400); // body 缺失
        // 全部拒绝：无规则残留
        assertThat(get(rulePath("invalid-service")).statusCode()).isEqualTo(404);
    }

    @Test
    void should_reject_reserved_route_rule_creation_over_http() throws Exception {
        String body = "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"reserved-service\","
                + "\"name\":\"canary\",\"targets\":[{\"selector\":{\"v\":\"c\"},\"unreleasedWeight\":100}]}";
        assertThat(post("/admin/v1/route-rules", body).statusCode()).isEqualTo(400);
    }

    @Test
    void should_rollback_canary_to_previous_weight() throws Exception {
        String canary = canaryPath("rollback-service");
        String rule = rulePath("rollback-service");
        json(post(canary, DEPLOY_BODY.formatted(1000)));
        long firstReleaseId = json(get(rule + "/releases?limit=1"))
                .get("releases")
                .get(0)
                .get("id")
                .asLong();
        JsonNode redeployed = json(post(canary, DEPLOY_BODY.formatted(3000)));
        assertThat(redeployed.get("targets").get(0).get("weight").asInt()).isEqualTo(3000);

        // 既有 rollback API（canary 规则的发布记录可回滚）：两列写回源快照
        JsonNode restored = json(post(rule + "/rollback", "{\"releaseId\":" + firstReleaseId + "}"));
        assertThat(restored.get("targets").get(0).get("weight").asInt()).isEqualTo(1000);
        assertThat(restored.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(1000);
        assertThat(restored.get("targets").get(1).get("weight").asInt()).isEqualTo(9000);
    }

    @Test
    void should_delete_canary_over_http_idempotently() throws Exception {
        String canary = canaryPath("delete-service");
        String rule = rulePath("delete-service");
        json(post(canary, DEPLOY_BODY.formatted(1000)));

        assertThat(send(HttpRequest.newBuilder(uri(canary)).DELETE(), true).statusCode())
                .isEqualTo(204);
        assertThat(get(rule).statusCode()).isEqualTo(404); // 规则删除 = 无治理意图
        assertThat(json(get(rule + "/releases")).get("releases").size()).isEqualTo(1); // 发布记录 append-only 保留
        assertThat(json(get("/admin/v1/audit-logs?operation=DELETE_CANARY"))
                        .get("logs")
                        .size())
                .isEqualTo(1);

        assertThat(send(HttpRequest.newBuilder(uri(canary)).DELETE(), true).statusCode())
                .isEqualTo(204); // 二次删除幂等
        assertThat(json(get("/admin/v1/audit-logs?operation=DELETE_CANARY"))
                        .get("logs")
                        .size())
                .isEqualTo(1); // 幂等 no-op 不追加审计
    }
}
