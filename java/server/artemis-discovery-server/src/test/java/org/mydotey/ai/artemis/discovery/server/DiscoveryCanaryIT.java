package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * canary 流量切分端到端 IT（v0.5 phase-4，roadmap 验收要点「canary 流量切分」）：
 * 进程内真实 registry + discovery + management——canary API 一键部署（weight=0 →
 * 金丝雀实例摘出视图；再部署提升权重 → 回归），Group filter 执行零变更复用 phase-3。
 */
class DiscoveryCanaryIT {

    private static final String CLUSTER = "cluster-a";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final ServiceKey ORDER =
            ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE).build();
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;

    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    private int registryPeerPort;
    private int managementGrpcPort;
    private int managementHttpPort;

    @AfterEach
    void tearDown() {
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
        }
        if (registryChannel != null) {
            registryChannel.shutdown();
        }
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
    void should_cut_canary_traffic_end_to_end() throws Exception {
        startAll();
        register("i-canary", Map.of("version", "canary"));
        register("i-stable", Map.of("version", "stable"));
        awaitUntil("both visible", () -> queryInstances().size() == 2);

        // canary 部署 weight=0：金丝雀组 0 承接摘出视图，稳定组兜底 10000 可见（dark launch）
        deployCanary(0);
        awaitUntil("canary dropped", () -> queryInstances().equals(List.of("i-stable")));

        // 再部署提升权重（weight=1000）：金丝雀组回归视图（0/非0 跨越 = 视图变化）
        deployCanary(1000);
        awaitUntil("canary restored", () -> queryInstances().size() == 2);

        // 结束灰度：先收回到 weight=0（金丝雀摘出），再 DELETE = 规则删除 → 无治理意图
        // → 金丝雀实例回归视图（删除事件传播 → filter 视图重建的真断言，非恒真）
        deployCanary(0);
        awaitUntil("canary dropped before finish", () -> queryInstances().equals(List.of("i-stable")));
        assertThat(deleteCanary().statusCode()).isEqualTo(204);
        assertThat(HTTP.send(getRuleManagement(), HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(404);
        awaitUntil("all visible after finish", () -> queryInstances().size() == 2);
    }

    // ---- 服务起停（形态同 DiscoveryGroupRoutingIT）----

    private void startAll() throws IOException {
        startRegistry();
        startManagement();
        startDiscovery();
    }

    private void startRegistry() throws IOException {
        int registryExternalPort = freePort();
        registryPeerPort = freePort();
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/discovery-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + registryExternalPort,
                        "--artemis.registry.internal.port=" + registryPeerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("registry-snapshot.bin"),
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
        managementGrpcPort = freePortUnchecked();
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/ejection-it-management.properties",
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
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
    }

    private int discoveryPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    // ---- 驱动 ----

    private void register(String instanceId, Map<String, String> metadata) {
        assertThat(registry.register(RegisterRequest.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                        .setKey(ORDER)
                                        .addInstances(instance(instanceId, metadata)))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private static Instance instance(String instanceId, Map<String, String> metadata) {
        Instance.Builder builder = Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(8080);
        metadata.forEach(builder::putMetadata);
        return builder.build();
    }

    private void deployCanary(int weight) throws Exception {
        assertThat(postManagement(
                                "/admin/v1/canary/" + NAMESPACE + "/" + SERVICE,
                                "{\"selector\":{\"version\":\"canary\"},\"weight\":" + weight + "}")
                        .statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> deleteCanary() throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/canary/"
                                + NAMESPACE + "/" + SERVICE))
                        .header("Authorization", AUTH)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 保留名规则经既有 route-rule API 可查（canary = 普通规则）。 */
    private HttpRequest getRuleManagement() {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/route-rules/"
                        + NAMESPACE + "/" + SERVICE + "/canary"))
                .header("Authorization", AUTH)
                .GET()
                .build();
    }

    private HttpResponse<String> postManagement(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Object queryAll() {
        return queryStub.query(QueryRequest.newBuilder().build());
    }

    private List<String> queryInstances() {
        var response =
                queryStub.query(QueryRequest.newBuilder().addServices(ORDER).build());
        List<String> ids = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            for (Instance instance : service.getInstances().getInstancesList()) {
                ids.add(instance.getInstanceId());
            }
        }
        return ids;
    }

    private static io.grpc.Status.Code statusOf(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return null;
        } catch (io.grpc.StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
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
            throw new UncheckedIOException(e);
        }
    }
}
