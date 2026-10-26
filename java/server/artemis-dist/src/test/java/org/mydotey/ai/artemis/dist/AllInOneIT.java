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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.server.upstream.GrpcLogicalInstanceSource;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;

/**
 * all-in-one 端到端（三服务完整形态）：同 JVM 起真实三上下文（各自随机端口、真实 gRPC/HTTP）——
 * registry 外部注册 → discovery 查询可见（网络互访非进程内短路）；治理链闭环（摘除 → 规则流 →
 * discovery 过滤 + 水位可见）、逻辑实例 union、console 页面与分页 API。registry 外部与 management
 * Admin API 默认认证开启，调用带默认 artemis/artemis Basic 头。
 */
class AllInOneIT {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final ServiceKey KEY =
            ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE).build();

    private ArtemisAllInOneServer.Node node;

    private ManagedChannel registryChannel;

    private ManagedChannel discoveryChannel;

    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;

    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 隔离 registry 快照（默认相对路径 data/registry-snapshot.bin 会跨运行残留、下次冷启动回放
        // 旧实例造成假绿）：每用例独立临时文件，经 registry 上下文 SCF 系统属性 source 生效
        System.setProperty(
                "artemis.registry.snapshot.path",
                tempDir.resolve("registry-snapshot.bin").toString());
        // 治理链收敛加速（规则流断连重连退避；生产默认值面向长稳态，IT 30s 窗口内要收敛）
        System.setProperty("artemis.discovery.management.reconnectBackoffMinMs", "200");
        System.setProperty("artemis.discovery.management.reconnectBackoffMaxMs", "1000");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("artemis.registry.snapshot.path");
        System.clearProperty("artemis.discovery.management.reconnectBackoffMinMs");
        System.clearProperty("artemis.discovery.management.reconnectBackoffMaxMs");
        closeChannels();
        if (node != null) {
            node.close();
        }
    }

    @Test
    void should_start_all_three_services_on_distinct_ports() throws Exception {
        node = ArtemisAllInOneServer.start(randomOptions());
        assertThat(node.registryGrpcPort()).isPositive();
        assertThat(node.registryHttpPort()).isPositive();
        assertThat(node.managementGrpcPort()).isPositive(); // 治理链闭环前提 = 必监听
        assertThat(node.managementHttpPort()).isPositive();
        assertThat(node.discoveryGrpcPort()).isPositive();
        assertThat(node.discoveryHttpPort()).isPositive();
        // 各上下文端口互不相同（独立注入，不串口）
        assertThat(List.of(
                        node.registryGrpcPort(),
                        node.registryHttpPort(),
                        node.managementGrpcPort(),
                        node.managementHttpPort(),
                        node.discoveryGrpcPort(),
                        node.discoveryHttpPort()))
                .doesNotHaveDuplicates();
    }

    @Test
    void should_serve_registered_instance_through_discovery() throws Exception {
        startNode();
        register("i1");
        awaitUntil("instance visible", () -> instance("i1") != null);
    }

    @Test
    void should_stop_all_contexts_when_closed() throws Exception {
        node = ArtemisAllInOneServer.start(randomOptions());
        node.close();
        assertThat(node.registry().isActive()).isFalse();
        assertThat(node.management().isActive()).isFalse();
        assertThat(node.discovery().isActive()).isFalse();
        // 重复 close 幂等
        node.close();
    }

    @Test
    void should_filter_discovered_instance_after_ejection_and_expose_watermark() throws Exception {
        startNode();
        register("i1");
        register("i2");
        awaitUntil("instances visible", () -> instance("i1") != null && instance("i2") != null);

        // 摘除 i1 → 规则流下发 → discovery 查询过滤生效（治理链在组合内闭环）
        post(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"i1\",\"reason\":\"maintenance\"}");
        awaitUntil("ejected instance filtered", () -> instance("i1") == null && instance("i2") != null);

        // 生效水位可见（roadmap v0.4 验收要点）：真实 discovery 订阅方应用最新版本后 ack
        awaitUntil(
                "watermark visible",
                () -> ruleStatus().get("currentVersion").getAsLong() > 0
                        && ruleStatus().get("appliedLatestCount").getAsInt() == 1);

        // 恢复 → 规则流再下发 → 视图还原
        post(
                "/admin/v1/ejections/recover",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"i1\"}");
        awaitUntil("recovered", () -> instance("i1") != null && instance("i2") != null);
    }

    @Test
    void should_discover_logical_instance_union() throws Exception {
        startNode();
        register("i1");
        put(
                "/admin/v1/logical-instances",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"li-1\",\"host\":\"10.9.9.1\",\"port\":8080,"
                        + "\"metadata\":{\"protocol\":\"http\"}}");
        // union：同 service 两来源并列（真实 cluster default + 保留 cluster logical，互不渗透）
        awaitUntil("real visible", () -> instancesOf("default").equals(List.of("i1")));
        awaitUntil(
                "logical visible",
                () -> instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).contains("li-1"));
    }

    @Test
    void should_serve_console_page_and_paged_services() throws Exception {
        startNode();
        register("i1");
        // console 静态页：未认证 401、认证 200（Admin API 认证默认开启）
        HttpResponse<String> unauthenticated =
                send(HttpRequest.newBuilder(uri("/")).GET(), false);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/")).GET(), true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).contains("text/html");
        assertThat(page.body()).contains("Artemis Console");

        // 服务列表分页/模糊（console API；组合视图 = 同进程 registry 事实）
        awaitUntil("service listed", () -> serviceTotal() >= 1);
        JsonObject services =
                json(get("/admin/v1/services?namespace=" + NAMESPACE + "&keyword=ORDER&offset=0&limit=2"));
        assertThat(services.get("total").getAsInt()).isEqualTo(1); // keyword 大小写不敏感
        assertThat(services.get("services").getAsJsonArray()).hasSize(1);
        assertThat(services.get("services")
                        .getAsJsonArray()
                        .get(0)
                        .getAsJsonObject()
                        .get("key")
                        .getAsJsonObject()
                        .get("service")
                        .getAsString())
                .isEqualTo(SERVICE);
    }

    // ---- 起停与驱动 ----

    private void startNode() throws IOException {
        node = ArtemisAllInOneServer.start(randomOptions());
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.registryGrpcPort())
                .usePlaintext()
                .build();
        registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth("artemis", "artemis")));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.discoveryGrpcPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
    }

    private static AllInOneOptions randomOptions() throws IOException {
        // peer 端口产品语义不接受 0 随机，预分配；其余端口随机缩小并行冲突面
        return AllInOneOptions.builder()
                .registryGrpcPort(0)
                .registryHttpPort(0)
                .registryManagementPort(0)
                .registryPeerPort(freePort())
                .managementGrpcPort(0)
                .managementHttpPort(0)
                .managementManagementPort(0)
                .discoveryGrpcPort(0)
                .discoveryHttpPort(0)
                .discoveryManagementPort(0)
                .build();
    }

    private void register(String instanceId) {
        RegisterResponse response = registryStub.register(RegisterRequest.newBuilder()
                .addServices(ServiceInstances.newBuilder()
                        .setKey(KEY)
                        .addInstances(Instance.newBuilder()
                                .setInstanceId(instanceId)
                                .setHost("10.0.0.1")
                                .setPort(8080))
                        .build())
                .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
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

    /** 全量查询中指定 cluster 的实例 id 列表（union 断言按 cluster 维度取）。 */
    private List<String> instancesOf(String cluster) {
        QueryResponse response =
                queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
        List<String> ids = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            if (cluster.equals(service.getCluster())) {
                for (Instance instance : service.getInstances().getInstancesList()) {
                    ids.add(instance.getInstanceId());
                }
            }
        }
        return ids;
    }

    /** 服务列表 total（console API 就绪探针；checked 异常包装以进 awaitUntil lambda）。 */
    private int serviceTotal() {
        try {
            return json(get("/admin/v1/services?namespace=" + NAMESPACE))
                    .get("total")
                    .getAsInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** GET /admin/v1/status/rules（phase-5 生效水位出口：当前版本 + 已生效节点数）。 */
    private JsonObject ruleStatus() {
        try {
            return JsonParser.parseString(get("/admin/v1/status/rules").body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), true);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)), true);
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).PUT(HttpRequest.BodyPublishers.ofString(body)), true);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, boolean authenticated) throws Exception {
        if (authenticated) {
            builder.header("Authorization", AUTH);
        }
        builder.header("Content-Type", "application/json");
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    private static Metadata basicAuth(String username, String password) {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue(username, password));
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
