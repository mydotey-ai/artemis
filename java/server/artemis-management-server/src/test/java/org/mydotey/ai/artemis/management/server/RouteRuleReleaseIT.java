package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * 两段式发布 HTTP 契约 IT（v0.5 phase-2，内存态）：release / rollback / diff /
 * releases 四端点 + 编辑通道收紧（create/update 不收生效列）+ 错误码与审计。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class RouteRuleReleaseIT extends ManagementHttpIT {

    private static final String NAMESPACE = "rr-release-it";
    private static final String SERVICE = "order-service";
    private static final String BASE = "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE;
    private static final String CREATE = "{\"namespace\":\"%s\",\"service\":\"%s\",\"name\":\"%s\","
            + "\"targets\":[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":%d},"
            + "{\"selector\":{},\"unreleasedWeight\":%d}]}";

    @Test
    void should_expose_release_api_contract_over_http() throws Exception {
        // create：不收生效列，初始 weight=0（两段式初始未发布）
        assertThat(post("/admin/v1/route-rules", CREATE.formatted(NAMESPACE, SERVICE, "beta", 20, 80))
                        .statusCode())
                .isEqualTo(201);
        JsonNode created = json(get(BASE + "/beta"));
        assertThat(created.get("targets").get(0).get("weight").asInt()).isZero();
        assertThat(created.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(20);

        // diff（发布预览）：基 = 空（未发布过），全量新增
        JsonNode before = json(get(BASE + "/beta/diff"));
        assertThat(before.get("baseReleaseId").isNull()).isTrue();
        assertThat(before.get("targets").size()).isEqualTo(2);
        assertThat(before.get("targets").get(0).get("currentWeight").isNull()).isTrue();
        assertThat(before.get("targets").get(0).get("nextWeight").asInt()).isEqualTo(20);

        // release：一个 API 完成灰度意图 → 两列相等
        JsonNode released = json(post(BASE + "/beta/release", ""));
        assertThat(released.get("targets").get(0).get("weight").asInt()).isEqualTo(20);
        assertThat(released.get("targets").get(1).get("weight").asInt()).isEqualTo(80);
        assertThat(released.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(20);

        // releases 列表：1 条 RELEASE 记录（id 降序），审计可查
        JsonNode releases = json(get(BASE + "/beta/releases"));
        assertThat(releases.get("releases").size()).isEqualTo(1);
        assertThat(releases.get("releases").get(0).get("type").asText()).isEqualTo("RELEASE");
        assertThat(releases.get("releases").get(0).get("rollbackTo").isNull()).isTrue();
        long firstId = releases.get("releases").get(0).get("id").asLong();
        JsonNode audits = json(get("/admin/v1/audit-logs?operation=RELEASE_ROUTE_RULE"));
        assertThat(audits.get("logs").get(0).get("target").asText())
                .isEqualTo("route-rule:" + NAMESPACE + "/" + SERVICE + "/beta");
        assertThat(audits.get("logs").get(0).get("detail").asText()).contains("releaseId=" + firstId);

        // 编辑（50/50）→ diff 显示权重变更（基 = 最新发布快照）
        assertThat(send(
                                HttpRequest.newBuilder(uri(BASE + "/beta"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(
                                                CREATE.formatted(NAMESPACE, SERVICE, "beta", 50, 50))),
                                true)
                        .statusCode())
                .isEqualTo(200);
        JsonNode editing = json(get(BASE + "/beta/diff"));
        assertThat(editing.get("baseReleaseId").isNull()).isTrue(); // 缺省基 = 最新发布（非指定）
        assertThat(editing.get("targets").get(0).get("currentWeight").asInt()).isEqualTo(20);
        assertThat(editing.get("targets").get(0).get("nextWeight").asInt()).isEqualTo(50);
        // 生效列不受编辑影响（继承语义）
        assertThat(json(get(BASE + "/beta")).get("targets").get(0).get("weight").asInt())
                .isEqualTo(20);

        // 二次发布 + 回滚到第一次：两列写回源快照
        post(BASE + "/beta/release", "");
        HttpResponse<String> rollback = post(BASE + "/beta/rollback", "{\"releaseId\":" + firstId + "}");
        assertThat(rollback.statusCode()).isEqualTo(200);
        JsonNode restored = json(rollback);
        assertThat(restored.get("targets").get(0).get("weight").asInt()).isEqualTo(20);
        assertThat(restored.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(20);
        assertThat(restored.get("targets").get(1).get("weight").asInt()).isEqualTo(80);

        JsonNode rollbackRecords = json(get(BASE + "/beta/releases"));
        assertThat(rollbackRecords.get("releases").get(0).get("type").asText()).isEqualTo("ROLLBACK");
        assertThat(rollbackRecords.get("releases").get(0).get("rollbackTo").asLong())
                .isEqualTo(firstId);

        // diff 指定历史 releaseId：回滚预览（基 = 该快照，next = 当前编辑）
        JsonNode againstFirst = json(get(BASE + "/beta/diff?releaseId=" + firstId));
        assertThat(againstFirst.get("baseReleaseId").asLong()).isEqualTo(firstId);
        assertThat(againstFirst.get("targets").get(0).get("currentWeight").asInt())
                .isEqualTo(20);
        assertThat(againstFirst.get("targets").get(0).get("nextWeight").asInt()).isEqualTo(20);
    }

    @Test
    void should_return_not_found_when_release_or_rollback_source_missing() throws Exception {
        assertThat(post(BASE + "/missing/release", "").statusCode()).isEqualTo(404);

        assertThat(post("/admin/v1/route-rules", CREATE.formatted(NAMESPACE, SERVICE, "gamma", 10, 90))
                        .statusCode())
                .isEqualTo(201);
        HttpResponse<String> rollback = post(BASE + "/gamma/rollback", "{\"releaseId\":404}");
        assertThat(rollback.statusCode()).isEqualTo(404);
        assertThat(objectMapper
                        .readValue(rollback.body(), tools.jackson.databind.JsonNode.class)
                        .get("error")
                        .asText())
                .isEqualTo("ROUTE_RULE_RELEASE_NOT_FOUND");

        // 规则存在但从未发布：diff 走空基（可预览），releases 空列表
        assertThat(json(get(BASE + "/gamma/diff")).get("targets").size()).isEqualTo(2);
        assertThat(json(get(BASE + "/gamma/releases")).get("releases").size()).isZero();

        assertThat(get(BASE + "/missing/diff").statusCode()).isEqualTo(404);
        assertThat(get(BASE + "/gamma/diff?releaseId=404").statusCode()).isEqualTo(404);
    }
}
