package org.mydotey.ai.artemis.dist;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 态跨重启端到端（roadmap v0.4 验收要点「摘除跨重启恢复」的 all-in-one 形态）：PG 容器 +
 * 三上下文组合（{@code spring.datasource.url} 经系统属性注入、仅 management 上下文消费）——
 * 摘除生效后整个组合 close → 同 DB 同端口新组合重启 → DB 摘除记录经规则流重下发、discovery
 * 过滤恢复、水位重建（registry 侧走快照回放自愈）。无 Docker 环境整类跳过（双方言语义由
 * phase-1–4 既有 IT 承载，组合场景单方言，D7-5）。
 */
@Testcontainers(disabledWithoutDocker = true)
class AllInOnePersistenceIT {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey KEY =
            org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setService(SERVICE)
                    .build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @TempDir
    Path tempDir;

    private ArtemisAllInOneServer.Node node;

    private ManagedChannel registryChannel;

    private ManagedChannel discoveryChannel;

    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;

    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    /** 两个组合实例复用（互连端点固定；重启重连链路稳定）。 */
    private AllInOneOptions options;

    @BeforeEach
    void setUp() throws IOException {
        // DB 态：经 Spring 标准途径（系统属性）进入三上下文共享 Environment，仅 management 消费
        System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES.getPassword());
        System.setProperty(
                "artemis.registry.snapshot.path",
                tempDir.resolve("registry-snapshot.bin").toString());
        System.setProperty("artemis.discovery.management.reconnectBackoffMinMs", "200");
        System.setProperty("artemis.discovery.management.reconnectBackoffMaxMs", "1000");
        options = AllInOneOptions.builder()
                .registryGrpcPort(0)
                .registryHttpPort(0)
                .registryManagementPort(0)
                .registryPeerPort(freePort())
                .managementGrpcPort(freePort())
                .managementHttpPort(0)
                .managementManagementPort(0)
                .discoveryGrpcPort(0)
                .discoveryHttpPort(0)
                .discoveryManagementPort(0)
                .build();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("spring.datasource.url");
        System.clearProperty("spring.datasource.username");
        System.clearProperty("spring.datasource.password");
        System.clearProperty("artemis.registry.snapshot.path");
        System.clearProperty("artemis.discovery.management.reconnectBackoffMinMs");
        System.clearProperty("artemis.discovery.management.reconnectBackoffMaxMs");
        closeChannels();
        if (node != null) {
            node.close();
        }
    }

    @Test
    void should_restore_ejections_across_all_in_one_restart_with_db() throws Exception {
        // 第一个组合：注册两实例、摘除其一 → 治理链生效（过滤 + 水位）
        node = ArtemisAllInOneServer.start(options);
        wireStubs();
        register("i1");
        register("i2");
        awaitUntil("instances visible", () -> instance("i1") != null && instance("i2") != null);
        post(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"i1\",\"reason\":\"maintenance\"}");
        awaitUntil("ejected filtered", () -> instance("i1") == null && instance("i2") != null);
        awaitUntil(
                "watermark visible",
                () -> ruleStatus().get("appliedLatestCount").getAsInt() == 1);

        // 整组合优雅停机（registry 终刷快照）→ 同 DB 同端口新组合重启
        node.close();
        closeChannels();
        node = ArtemisAllInOneServer.start(options);
        wireStubs();

        // 跨重启恢复：registry 快照回放补齐事实 + DB 摘除记录重下发 → discovery 过滤恢复、水位重建
        awaitUntil("restored and filtered", () -> instance("i1") == null && instance("i2") != null);
        awaitUntil(
                "watermark rebuilt",
                () -> ruleStatus().get("currentVersion").getAsLong() > 0
                        && ruleStatus().get("appliedLatestCount").getAsInt() == 1);

        // DB 态可观测（management persistence 状态 = DATABASE/PostgreSQL）
        JsonObject persistence = json(get("/admin/v1/status/persistence"));
        assertThat(persistence.get("mode").getAsString()).isEqualTo("DATABASE");
        assertThat(persistence.get("databaseProduct").getAsString()).isEqualTo("PostgreSQL");
    }

    // ---- 起停与驱动 ----

    private void wireStubs() {
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.registryGrpcPort())
                .usePlaintext()
                .build();
        registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth()));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.discoveryGrpcPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
    }

    private void register(String instanceId) {
        RegisterResponse response = registryStub.register(RegisterRequest.newBuilder()
                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                        .setKey(KEY)
                        .addInstances(Instance.newBuilder()
                                .setInstanceId(instanceId)
                                .setHost("10.0.0.1")
                                .setPort(8080))
                        .build())
                .build());
        assertThat(response.getStatus().getAck()).isEqualTo(org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_SUCCESS);
    }

    private QueryResponse queryAll() {
        return queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
    }

    private Instance instance(String instanceId) {
        QueryResponse response = queryAll();
        if (response.getData().getServicesCount() == 0) {
            return null;
        }
        for (Instance instance :
                response.getData().getServices(0).getInstances().getInstancesList()) {
            if (instance.getInstanceId().equals(instanceId)) {
                return instance;
            }
        }
        return null;
    }

    private JsonObject ruleStatus() {
        try {
            return JsonParser.parseString(get("/admin/v1/status/rules").body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + node.managementHttpPort() + path);
    }

    private JsonObject json(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static Metadata basicAuth() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        return headers;
    }

    private static Status.Code statusOf(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return null;
        } catch (StatusRuntimeException e) {
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

    private void closeChannels() {
        if (registryChannel != null) {
            registryChannel.shutdown();
            registryChannel = null;
        }
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }
}
