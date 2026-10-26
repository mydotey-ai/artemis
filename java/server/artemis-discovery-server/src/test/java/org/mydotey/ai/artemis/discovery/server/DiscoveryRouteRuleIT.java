package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.infrastructure.RouteRuleSynchronizer;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 两段式发布端到端 IT（v0.5 phase-2，roadmap 验收要点「灰度发布端到端（一个 API 完成）、
 * 回滚生效」）：进程内真实 registry + discovery + management——create + release（一个
 * API）→ discovery 规则视图更新 + 生效水位对齐；编辑再发布 → rollback → 视图还原 +
 * 水位推进。规则缓存经 RouteRuleSynchronizer bean 直读断言（无执行点，phase-3 起有
 * 过滤语义出口）。
 */
class DiscoveryRouteRuleIT {

    private static final String CLUSTER = "cluster-a";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;
    private int registryPeerPort;
    private int managementGrpcPort;
    private int managementHttpPort;
    private int snapshotGeneration;

    @AfterEach
    void tearDown() {
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        if (registryContext != null) {
            registryContext.close();
        }
        if (managementContext != null) {
            managementContext.close();
        }
    }

    @Test
    void should_release_end_to_end_via_single_api() throws Exception {
        startAll();

        // create（初始生效列 0）+ 编辑权重
        postManagement(
                "/admin/v1/route-rules",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"name\":\"beta\",\"targets\":["
                        + "{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":20},"
                        + "{\"selector\":{},\"unreleasedWeight\":80}]}");
        RouteRuleSynchronizer synchronizer = discoveryContext.getBean(RouteRuleSynchronizer.class);
        awaitUntil("snapshot cached", () -> synchronizer.rules().size() == 1);
        assertThat(targetWeight(synchronizer, "beta", "group=beta")).isZero(); // 未发布：生效列 0

        // release：一个 API 完成灰度意图 → discovery 视图生效 + 水位对齐
        postManagement("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta/release", "");
        awaitUntil("released weight visible", () -> targetWeight(synchronizer, "beta", "group=beta") == 20);
        awaitUntil("fallback weight visible", () -> targetWeight(synchronizer, "beta", "") == 80);
        awaitUntil(
                "watermark at latest",
                () -> routeRuleStatus().get("appliedLatestCount").getAsInt() == 1);
        com.google.gson.JsonObject status = routeRuleStatus();
        long currentVersion = status.get("currentVersion").getAsLong();
        assertThat(currentVersion).isPositive();
        assertThat(status.get("subscribers").getAsJsonArray().size()).isEqualTo(1);
        assertThat(status.get("subscribers")
                        .getAsJsonArray()
                        .get(0)
                        .getAsJsonObject()
                        .get("appliedVersion")
                        .getAsLong())
                .isEqualTo(currentVersion);
    }

    @Test
    void should_rollback_end_to_end_with_view_restored() throws Exception {
        startAll();

        postManagement(
                "/admin/v1/route-rules",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"name\":\"beta\",\"targets\":["
                        + "{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":30},"
                        + "{\"selector\":{},\"unreleasedWeight\":70}]}");
        postManagement("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta/release", "");
        long firstReleaseId = releases().get(0);

        // 第二轮编辑 + 发布（100/0），回滚预览可见
        postManagementPut(
                "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"name\":\"beta\",\"targets\":["
                        + "{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":100},"
                        + "{\"selector\":{},\"unreleasedWeight\":0}]}");
        postManagement("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta/release", "");
        RouteRuleSynchronizer synchronizer = discoveryContext.getBean(RouteRuleSynchronizer.class);
        awaitUntil("second release visible", () -> targetWeight(synchronizer, "beta", "group=beta") == 100);
        awaitUntil(
                "watermark at latest",
                () -> routeRuleStatus().get("appliedLatestCount").getAsInt() == 1);

        // rollback：discovery 视图还原源快照 + 水位推进
        postManagement(
                "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta/rollback",
                "{\"releaseId\":" + firstReleaseId + "}");
        awaitUntil(
                "rolled back",
                () -> targetWeight(synchronizer, "beta", "group=beta") == 30
                        && targetWeight(synchronizer, "beta", "") == 70);
        awaitUntil("watermark advanced", () -> {
            com.google.gson.JsonObject status = routeRuleStatus();
            return status.get("currentVersion").getAsLong() > 0
                    && status.get("appliedLatestCount").getAsInt() == 1;
        });
    }

    // ---- 断言辅助 ----

    /** 取规则视图目标生效权重：selectorKey 形如 "group=beta"，空串 = 兜底目标。 */
    private static int targetWeight(RouteRuleSynchronizer synchronizer, String name, String selectorKey) {
        return synchronizer
                .rules()
                .find(new org.mydotey.ai.artemis.discovery.domain.ServiceKey(NAMESPACE, SERVICE), name)
                .map(rule -> rule.targets().stream()
                        .filter(target -> selectorOf(target).equals(selectorKey))
                        .findFirst()
                        .map(RouteRule.RouteRuleTarget::weight)
                        .orElse(-1))
                .orElse(-1);
    }

    private static String selectorOf(RouteRule.RouteRuleTarget target) {
        return target.selector().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private java.util.List<Long> releases() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/route-rules/" + NAMESPACE + "/"
                        + SERVICE + "/beta/releases"))
                .header("Authorization", AUTH)
                .GET()
                .build();
        var array = com.google.gson.JsonParser.parseString(
                        HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body())
                .getAsJsonObject()
                .get("releases")
                .getAsJsonArray();
        return java.util.stream.IntStream.range(0, array.size())
                .mapToObj(i -> array.get(i).getAsJsonObject().get("id").getAsLong())
                .toList();
    }

    /** GET /admin/v1/status/rules 的 routeRules 水位节。 */
    private com.google.gson.JsonObject routeRuleStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/status/rules"))
                    .header("Authorization", AUTH)
                    .GET()
                    .build();
            return com.google.gson.JsonParser.parseString(HTTP.send(request, HttpResponse.BodyHandlers.ofString())
                            .body())
                    .getAsJsonObject()
                    .get("routeRules")
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 服务起停 ----

    private void startAll() throws IOException {
        startRegistry();
        startManagement();
        startDiscovery();
    }

    private void startRegistry() throws IOException {
        int registryExternalPort = freePort();
        registryPeerPort = freePort();
        snapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/ack-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + registryExternalPort,
                        "--artemis.registry.internal.port=" + registryPeerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.snapshot.path="
                                + tempDir.resolve("registry-snapshot-" + snapshotGeneration + ".bin"),
                        "--artemis.registry.lease.ttlMs=600000",
                        "--artemis.registry.shutdown.drainMs=0");
    }

    private void startManagement() {
        managementGrpcPort = freePortUnchecked(); // 单用例内固定：重启场景不涉及
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/ack-it-management.properties",
                        "--spring.application.name=artemis-management",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + managementGrpcPort,
                        "--artemis.management.grpc.port=" + managementGrpcPort,
                        "--artemis.management.registry.endpoint=127.0.0.1:1"); // 惰性，规则流不触达
        managementHttpPort = managementContext.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private void startDiscovery() {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--spring.main.web-application-type=none",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=0",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--artemis.discovery.registry.clusters={\"" + CLUSTER + "\":\"127.0.0.1:" + registryPeerPort
                                + "\"}",
                        "--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort,
                        "--artemis.discovery.management.reconnectBackoffMinMs=200",
                        "--artemis.discovery.management.reconnectBackoffMaxMs=1000");
    }

    // ---- HTTP 驱动 ----

    private void postManagement(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + managementHttpPort + path))
                    .header("Authorization", AUTH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void postManagementPut(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + managementHttpPort + path))
                    .header("Authorization", AUTH)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within timeout: " + what);
            }
            Thread.sleep(50);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freePortUnchecked() {
        try {
            return freePort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
