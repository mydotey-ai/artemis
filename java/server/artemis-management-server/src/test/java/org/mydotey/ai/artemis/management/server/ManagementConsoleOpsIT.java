package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * console 管理版操作闭环 IT（v0.5 phase-5）：同 JVM 真实 registry + 真实 management（随机端口），
 * 经真实 gRPC 注册与真实 HTTP + Basic 认证驱动页面可达性与 console 所驱动治理 API 的操作闭环
 * （摘除/恢复、canary、两段发布）与视图/审计可查。namespace 按用例隔离（无跨用例清理）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementConsoleOpsIT extends ManagementHttpIT {

    private static final String NAMESPACE = "console-ops-it";

    private ManagedChannel externalChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;

    @DynamicPropertySource
    static void registryEndpoint(DynamicPropertyRegistry properties) {
        properties.add("artemis.management.registry.endpoint", () -> "localhost:" + RegistryFixture.internalPort());
    }

    @BeforeEach
    void setUp() {
        externalChannel = ManagedChannelBuilder.forAddress("localhost", RegistryFixture.externalPort())
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        registry = RegistryServiceGrpc.newBlockingStub(externalChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_serve_management_console_with_management_views_when_root_requested() throws Exception {
        HttpResponse<String> unauthenticated =
                send(HttpRequest.newBuilder(uri("/")).GET(), false);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/")).GET(), true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).contains("text/html");
        assertThat(page.body()).contains("Artemis Console");
        // 管理版导航：五个操作/视图面（发布/摘除、订阅关系、审计、保护态）
        assertThat(page.body()).contains("路由规则", "摘除记录", "订阅关系", "审计", "保护态");
    }

    @Test
    void should_eject_and_recover_instance_over_http_in_console_loop() throws Exception {
        String service = "ops-eject-web";
        registerService(service, "i1", "i2");

        json(post(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + service
                        + "\",\"instanceId\":\"i1\",\"reason\":\"it\"}"));

        JsonNode active = json(get("/admin/v1/ejections?activeOnly=true")).get("records");
        List<JsonNode> own = filterByNamespace(active);
        assertThat(own.size()).isEqualTo(1);
        assertThat(own.get(0).get("instanceId").asText()).isEqualTo("i1");

        // 有效视图过滤（详情页驱动同口径）
        JsonNode effective = json(get("/admin/v1/services/" + NAMESPACE + "/" + service));
        assertThat(effective.get("effectiveInstanceCount").asInt()).isEqualTo(1);

        post(
                "/admin/v1/ejections/recover",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + service
                        + "\",\"instanceId\":\"i1\"}");
        assertThat(filterByNamespace(
                                json(get("/admin/v1/ejections?activeOnly=true")).get("records"))
                        .size())
                .isZero();

        // 审计行数按目标过滤断言：共享 context（TestContext 缓存）下内存审计跨 IT 类/方法累积
        assertThat(countByTarget(
                        json(get("/admin/v1/audit-logs?operation=EJECT")).get("logs"), service))
                .isEqualTo(1);
        assertThat(countByTarget(
                        json(get("/admin/v1/audit-logs?operation=RECOVER")).get("logs"), service))
                .isEqualTo(1);
    }

    @Test
    void should_deploy_and_finish_canary_over_http_in_console_loop() throws Exception {
        String service = "ops-canary-web";
        String rule = "/admin/v1/route-rules/" + NAMESPACE + "/" + service + "/canary";

        JsonNode deployed = json(post(
                "/admin/v1/canary/" + NAMESPACE + "/" + service,
                "{\"selector\":{\"version\":\"canary\"},\"weight\":1000}"));
        assertThat(deployed.get("targets").get(0).get("weight").asInt()).isEqualTo(1000);
        assertThat(deployed.get("targets").get(0).get("unreleasedWeight").asInt())
                .isEqualTo(1000); // 两列一致 = 已发布态

        assertThat(get(rule).statusCode()).isEqualTo(200); // 规则页可查（回滚入口同源）
        assertThat(send(
                                HttpRequest.newBuilder(uri("/admin/v1/canary/" + NAMESPACE + "/" + service))
                                        .DELETE(),
                                true)
                        .statusCode())
                .isEqualTo(204);
        assertThat(get(rule).statusCode()).isEqualTo(404); // 结束灰度 = 规则删除
        assertThat(countByTarget(
                        json(get("/admin/v1/audit-logs?operation=DEPLOY_CANARY"))
                                .get("logs"),
                        service))
                .isEqualTo(1);
    }

    @Test
    void should_walk_two_phase_release_over_http_in_console_loop() throws Exception {
        String rule = "/admin/v1/route-rules/" + NAMESPACE + "/ops-release-web/split";
        json(post(
                "/admin/v1/route-rules",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"ops-release-web\",\"name\":\"split\","
                        + "\"targets\":[{\"selector\":{\"group\":\"a\"},\"unreleasedWeight\":3000},"
                        + "{\"selector\":{},\"unreleasedWeight\":7000}]}"));

        // 发布前 diff：基为空（未发布过）→ current null 全量新增
        JsonNode before = json(get(rule + "/diff")).get("targets");
        assertThat(before.get(0).path("currentWeight").isNull()).isTrue(); // path：缺失/显式 null 均成立
        assertThat(before.get(0).path("nextWeight").asInt()).isEqualTo(3000);

        JsonNode released = json(post(rule + "/release", null));
        assertThat(released.get("targets").get(0).get("weight").asInt()).isEqualTo(3000); // 编辑列 → 生效列

        JsonNode after = json(get(rule + "/diff")).get("targets");
        assertThat(after.get(0).path("currentWeight").asInt()).isEqualTo(3000); // 两列一致
        assertThat(after.get(0).path("nextWeight").asInt()).isEqualTo(3000);

        JsonNode releases = json(get(rule + "/releases")).get("releases");
        assertThat(releases.size()).isEqualTo(1);
        assertThat(releases.get(0).get("type").asText()).isEqualTo("RELEASE");
        assertThat(countByTarget(
                        json(get("/admin/v1/audit-logs?operation=RELEASE_ROUTE_RULE"))
                                .get("logs"),
                        "ops-release-web"))
                .isEqualTo(1);
    }

    @Test
    void should_return_protection_and_audit_views_over_http() throws Exception {
        JsonNode protection = json(get("/admin/v1/status/protection"));
        assertThat(protection.get("node").get("protecting").isBoolean()).isTrue();
        assertThat(protection.get("services").isArray()).isTrue();
        assertThat(json(get("/admin/v1/status/protection?includeNormal=true"))
                        .get("node")
                        .isObject())
                .isTrue();

        registerService("ops-view-web", "i1");
        post(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE
                        + "\",\"service\":\"ops-view-web\",\"instanceId\":\"i1\",\"reason\":\"it\"}");
        JsonNode audit = json(get("/admin/v1/audit-logs?operation=EJECT")).get("logs");
        assertThat(countByTarget(audit, "ops-view-web")).isEqualTo(1);
    }

    /** 审计行计数（target 子串过滤）：共享 context 下全局审计跨用例累积，断言须限定本用例目标。 */
    private static int countByTarget(JsonNode logs, String service) {
        int count = 0;
        for (JsonNode log : logs) {
            if (log.get("target").asText().contains(service)) {
                count++;
            }
        }
        return count;
    }

    /** 生效摘除按本 IT namespace 过滤（同类共享 context 全局态，跨 IT 类残留隔离）。 */
    private static List<JsonNode> filterByNamespace(JsonNode records) {
        List<JsonNode> own = new ArrayList<>();
        for (JsonNode record : records) {
            if (NAMESPACE.equals(record.path("namespace").asText(null))) {
                own.add(record);
            }
        }
        return own;
    }

    private void registerService(String service, String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder()
                .setKey(ServiceKey.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setService(service)
                        .build());
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        registry.register(RegisterRequest.newBuilder().addServices(services).build());
    }
}
