package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 规则下发闭环端到端（v0.4 phase-5）：进程内真实 registry + discovery + management——
 * 规则变更 → discovery 生效 → management 生效水位可见（哪个版本、多少节点已生效）、
 * management 重启重连重新 ack、多 discovery 节点生效计数。
 */
class DiscoveryRuleAckLoopIT {

    private static final String CLUSTER = "cluster-a";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey ORDER =
            org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setService(SERVICE)
                    .build();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;
    private ConfigurableApplicationContext discoveryContext2;

    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private ManagedChannel discoveryChannel2;

    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub2;

    private int registryExternalPort;
    private int registryPeerPort;
    private int managementGrpcPort;
    private int managementHttpPort;
    private int snapshotGeneration;

    @AfterEach
    void tearDown() {
        closeDiscovery();
        if (registryChannel != null) {
            registryChannel.shutdown();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    private void closeDiscovery() {
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
        if (discoveryChannel2 != null) {
            discoveryChannel2.shutdown();
            discoveryChannel2 = null;
        }
        if (discoveryContext != null) {
            discoveryContext.close();
            discoveryContext = null;
        }
        if (discoveryContext2 != null) {
            discoveryContext2.close();
            discoveryContext2 = null;
        }
        if (managementContext != null) {
            managementContext.close();
            managementContext = null;
        }
    }

    @Test
    void should_expose_rule_watermark_when_ejection_applied() throws Exception {
        startAll();
        register("i1");
        register("i2");
        awaitUntil("both visible", () -> queryInstances(queryStub).size() == 2);

        eject("i1", "maintenance");
        awaitUntil("query filtered", () -> queryInstances(queryStub).equals(List.of("i2")));

        // 生效水位：版本 > 0、单一订阅方已应用当前版本、生效节点数 = 1
        awaitUntil("watermark at latest", () -> {
            com.google.gson.JsonObject status = ruleStatus();
            return status.get("currentVersion").getAsLong() > 0
                    && status.get("appliedLatestCount").getAsInt() == 1;
        });
        com.google.gson.JsonObject status = ruleStatus();
        long currentVersion = status.get("currentVersion").getAsLong();
        assertThat(status.get("subscribers").getAsJsonArray()).hasSize(1);
        var subscriber = status.get("subscribers").getAsJsonArray().get(0).getAsJsonObject();
        assertThat(subscriber.get("appliedVersion").getAsLong()).isEqualTo(currentVersion);
        assertThat(subscriber.get("atLatest").getAsBoolean()).isTrue();
        assertThat(subscriber.get("subscriberId").getAsString()).startsWith("artemis-discovery@");
    }

    @Test
    void should_reack_after_reconnect_when_management_restarts() throws Exception {
        startAll();
        register("i1");
        awaitUntil("visible", () -> queryInstances(queryStub).equals(List.of("i1")));

        eject("i1", "maintenance");
        awaitUntil("query filtered", () -> queryInstances(queryStub).isEmpty());
        awaitUntil(
                "watermark at latest",
                () -> ruleStatus().get("appliedLatestCount").getAsInt() == 1);

        // management 停：规则冻结（fail-open 继续过滤），水位源不可查
        managementContext.close();
        managementContext = null;
        Thread.sleep(1_000); // 越过断流退避首轮，进入「management 不可用」窗口

        // management 重启（内存态清零 → 版本归零）：discovery 重连收空快照 + 重新 ack，
        // 水位重新一致（currentVersion = appliedVersion = 0）
        startManagement();
        awaitUntil("empty snapshot converged", () -> queryInstances(queryStub).equals(List.of("i1")));
        awaitUntil("re-acked at latest", () -> {
            com.google.gson.JsonObject status = ruleStatus();
            return status.get("currentVersion").getAsLong() == 0
                    && status.get("appliedLatestCount").getAsInt() == 1;
        });
    }

    @Test
    void should_count_effective_nodes_when_multiple_discovery_subscribers() throws Exception {
        startAll();
        register("i1");
        register("i2");
        awaitUntil("both visible", () -> queryInstances(queryStub).size() == 2);

        eject("i1", "maintenance");
        awaitUntil("first node filtered", () -> queryInstances(queryStub).equals(List.of("i2")));
        awaitUntil("one node at latest", () -> {
            com.google.gson.JsonObject status = ruleStatus();
            return status.get("subscribers").getAsJsonArray().size() == 1
                    && status.get("appliedLatestCount").getAsInt() == 1;
        });

        // 第二个 discovery 节点加入：订阅登记后 ack 到位 → 生效节点数 = 2
        startDiscovery2();
        awaitUntil("second node filtered", () -> queryInstances(queryStub2).equals(List.of("i2")));
        awaitUntil("two nodes at latest", () -> {
            com.google.gson.JsonObject status = ruleStatus();
            return status.get("subscribers").getAsJsonArray().size() == 2
                    && status.get("appliedLatestCount").getAsInt() == 2;
        });

        // 再变更：两节点各自 ack 新版本，计数维持 2
        recover("i1");
        awaitUntil(
                "both restored",
                () -> queryInstances(queryStub).size() == 2
                        && queryInstances(queryStub2).size() == 2);
        awaitUntil("two nodes at new version", () -> {
            com.google.gson.JsonObject status = ruleStatus();
            return status.get("currentVersion").getAsLong() > 0
                    && status.get("appliedLatestCount").getAsInt() == 2;
        });
    }

    // ---- 服务起停 ----

    private void startAll() throws IOException {
        startRegistry();
        startManagement();
        startDiscovery();
    }

    private void startRegistry() throws IOException {
        registryExternalPort = freePort();
        registryPeerPort = freePort();
        snapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        // 隔离 discovery 测试 classpath 的 application.properties（健康组等键冲突）
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
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", registryExternalPort)
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        registry = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void startManagement() {
        if (managementGrpcPort == 0) {
            managementGrpcPort = freePortUnchecked(); // 固定预分配：重启复用同端口
        }
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        // 同 registry：隔离 discovery 测试 classpath 配置
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
        discoveryContext = startDiscoveryContext();
        discoveryChannel = discoveryChannel(discoveryContext);
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
    }

    private void startDiscovery2() {
        discoveryContext2 = startDiscoveryContext();
        discoveryChannel2 = discoveryChannel(discoveryContext2);
        queryStub2 = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel2);
    }

    private ConfigurableApplicationContext startDiscoveryContext() {
        return new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
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

    private ManagedChannel discoveryChannel(ConfigurableApplicationContext context) {
        int port = context.getBeansOfType(GrpcServerLifecycle.class)
                .values()
                .iterator()
                .next()
                .getPort();
        return ManagedChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext()
                .build();
    }

    // ---- 驱动 ----

    private void register(String instanceId) {
        assertThat(registry.register(RegisterRequest.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                        .setKey(ORDER)
                                        .addInstances(instance(instanceId)))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void eject(String instanceId, String reason) throws Exception {
        postManagement(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE
                        + "\",\"service\":\"" + SERVICE + "\",\"instanceId\":\"" + instanceId + "\",\"reason\":\""
                        + reason
                        + "\"}");
    }

    private void recover(String instanceId) throws Exception {
        postManagement(
                "/admin/v1/ejections/recover",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"" + instanceId + "\"}");
    }

    private void postManagement(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** GET /admin/v1/status/rules（phase-5 生效水位出口；gson 解析）。 */
    private com.google.gson.JsonObject ruleStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/status/rules"))
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

    private List<String> queryInstances(DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub stub) {
        var response = stub.query(QueryRequest.newBuilder().addServices(ORDER).build());
        List<String> ids = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            for (Instance instance : service.getInstances().getInstancesList()) {
                ids.add(instance.getInstanceId());
            }
        }
        return ids;
    }

    private static Instance instance(String instanceId) {
        return Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(8080)
                .build();
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timeout waiting: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }

    private static int freePortUnchecked() {
        try {
            return freePort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
