package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceKey;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 服务级集成测试：同 JVM 起真实 registry-server（随机端口）+ 真实 management-server（随机 HTTP 端口），
 * 经真实 gRPC 注册/查询与真实 HTTP + Basic 认证驱动管理面有效视图与状态 API。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementAdminIT {

    private static final String NAMESPACE = "mgmt-it";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    private ManagedChannel externalChannel;
    private ManagedChannel internalChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub registryQuery;

    @DynamicPropertySource
    static void registryEndpoint(DynamicPropertyRegistry properties) {
        // v0.2：management 只读查询改连 registry 内部 peer 端口（端点显式配置，无默认值）
        properties.add("artemis.management.registry.endpoint", () -> "localhost:" + RegistryFixture.internalPort());
    }

    @AfterAll
    static void stopRegistry() {
        RegistryFixture.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        externalChannel = ManagedChannelBuilder.forAddress("localhost", RegistryFixture.externalPort())
                .usePlaintext()
                .build();
        internalChannel = ManagedChannelBuilder.forAddress("localhost", RegistryFixture.internalPort())
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        var interceptor = MetadataUtils.newAttachHeadersInterceptor(headers);
        // 注册走外部端口（外部账户）；内部认证默认关闭，查询无需凭证
        registry = RegistryServiceGrpc.newBlockingStub(externalChannel).withInterceptors(interceptor);
        registryQuery = RegistryQueryServiceGrpc.newBlockingStub(internalChannel);
        // 隔离：清掉上一用例的注册数据与摘除记录
        registry.deregister(DeregisterRequest.newBuilder()
                .addInstances(instanceKey("i1"))
                .addInstances(instanceKey("i2"))
                .build());
        post("/admin/v1/services/" + NAMESPACE + "/" + SERVICE + "/instances/i1/recover", null);
        post("/admin/v1/services/" + NAMESPACE + "/" + SERVICE + "/instances/i2/recover", null);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_require_auth_when_admin_api_called_without_credentials() throws Exception {
        assertThat(get("/admin/v1/services", false).statusCode()).isEqualTo(401);
        assertThat(get("/admin/v1/services", true).statusCode()).isEqualTo(200);
    }

    @Test
    void should_query_registry_when_management_uses_internal_port() {
        assertThat(registryQuery
                        .listServices(ListServicesRequest.newBuilder()
                                .setNamespace(NAMESPACE)
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    @Test
    void should_list_services_from_registry_when_registered() throws Exception {
        register("i1", "i2");

        JsonNode services =
                json(get("/admin/v1/services?namespace=" + NAMESPACE, true)).get("services");

        assertThat(services.size()).isEqualTo(1);
        assertThat(services.get(0).get("key").get("namespace").asText()).isEqualTo(NAMESPACE);
        assertThat(services.get(0).get("instanceCount").asInt()).isEqualTo(2);
        assertThat(services.get(0).get("ejectedCount").asInt()).isZero();
    }

    @Test
    void should_return_effective_view_when_instance_ejected() throws Exception {
        register("i1", "i2");

        assertThat(post(ejectPath("i1"), "{\"reason\":\"maintenance\"}").statusCode())
                .isEqualTo(200);

        JsonNode detail = json(get(detailPath(), true));
        assertThat(detail.get("instanceCount").asInt()).isEqualTo(2);
        assertThat(detail.get("ejectedCount").asInt()).isEqualTo(1);
        assertThat(detail.get("effectiveInstanceCount").asInt()).isEqualTo(1);
        assertThat(detail.get("instances").size()).isEqualTo(1);
        assertThat(detail.get("instances").get(0).get("instanceId").asText()).isEqualTo("i2");
    }

    @Test
    void should_show_ejected_instance_when_include_ejected() throws Exception {
        register("i1", "i2");
        post(ejectPath("i1"), "{\"reason\":\"maintenance\"}");

        JsonNode detail = json(get(detailPath() + "?includeEjected=true", true));

        assertThat(detail.get("instances").size()).isEqualTo(2);
        JsonNode ejected = detail.get("instances").get(0);
        assertThat(ejected.get("instanceId").asText()).isEqualTo("i1");
        assertThat(ejected.get("ejected").asBoolean()).isTrue();
        assertThat(ejected.get("reason").asText()).isEqualTo("maintenance");
        assertThat(ejected.get("ejectedAt").isNull()).isFalse();
    }

    @Test
    void should_keep_registry_facts_when_instance_ejected() throws Exception {
        register("i1", "i2");
        post(ejectPath("i1"), "{\"reason\":\"maintenance\"}");

        var response = registryQuery.getServiceInstances(
                GetServiceInstancesRequest.newBuilder().setKey(serviceKey()).build());

        assertThat(response.getData().getInstancesList())
                .extracting(Instance::getInstanceId)
                .containsExactlyInAnyOrder("i1", "i2");
    }

    @Test
    void should_restore_effective_view_when_recovered() throws Exception {
        register("i1", "i2");
        post(ejectPath("i1"), "{\"reason\":\"maintenance\"}");

        assertThat(post(recoverPath("i1"), null).statusCode()).isEqualTo(204);

        JsonNode detail = json(get(detailPath(), true));
        assertThat(detail.get("ejectedCount").asInt()).isZero();
        assertThat(detail.get("instances").size()).isEqualTo(2);
    }

    @Test
    void should_be_idempotent_when_ejecting_twice_over_http() throws Exception {
        register("i1");

        JsonNode first = json(post(ejectPath("i1"), "{\"reason\":\"first\"}"));
        JsonNode second = json(post(ejectPath("i1"), "{\"reason\":\"second\"}"));

        assertThat(second.get("reason").asText()).isEqualTo("first");
        assertThat(second.get("ejectedAt").asText())
                .isEqualTo(first.get("ejectedAt").asText());
    }

    @Test
    void should_return_400_when_path_params_blank() throws Exception {
        assertThat(post("/admin/v1/services/%20/" + SERVICE + "/instances/i1/eject", null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(post("/admin/v1/services/" + NAMESPACE + "/" + SERVICE + "/instances/%20/eject", null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(post("/admin/v1/services/" + NAMESPACE + "/%20/instances/i1/eject", null)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void should_keep_client_error_semantics_when_unknown_route_or_bad_param() throws Exception {
        assertThat(get("/admin/v1/unknown", true).statusCode()).isEqualTo(404);
        assertThat(get(detailPath() + "?includeEjected=abc", true).statusCode()).isEqualTo(400);
    }

    @Test
    void should_expose_lease_status_when_status_api_called() throws Exception {
        register("i1");

        JsonNode status = json(get("/admin/v1/status/leases?namespace=" + NAMESPACE, true));

        assertThat(status.get("protection")).isNotNull();
        assertThat(StreamSupport.stream(status.get("leases").spliterator(), false)
                        .map(lease -> lease.get("instanceId").asText()))
                .contains("i1");
    }

    @Test
    void should_mask_sensitive_config_when_config_status_api_called() throws Exception {
        JsonNode config = json(get("/admin/v1/status/config", true));

        JsonNode password = StreamSupport.stream(config.get("properties").spliterator(), false)
                .filter(property -> "artemis.registry.auth.password"
                        .equals(property.get("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(password.get("value").asText()).isEqualTo("******");
        assertThat(password.get("defaultValue").asText()).isEqualTo("******");
    }

    private void register(String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(serviceKey());
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

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        return send(builder, authenticated);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return send(builder, true);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, boolean authenticated) throws Exception {
        if (authenticated) {
            builder.header("Authorization", AUTH);
        }
        builder.header("Content-Type", "application/json");
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + httpPort + path);
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    private static String detailPath() {
        return "/admin/v1/services/" + NAMESPACE + "/" + SERVICE;
    }

    private static String ejectPath(String instanceId) {
        return detailPath() + "/instances/" + instanceId + "/eject";
    }

    private static String recoverPath(String instanceId) {
        return detailPath() + "/instances/" + instanceId + "/recover";
    }

    private static ServiceKey serviceKey() {
        return ServiceKey.newBuilder()
                .setNamespace(NAMESPACE)
                .setService(SERVICE)
                .build();
    }

    private static InstanceKey instanceKey(String instanceId) {
        return InstanceKey.newBuilder()
                .setService(serviceKey())
                .setInstanceId(instanceId)
                .build();
    }

    /** registry fixture：惰性启动（DynamicPropertySource 求值时），外部/内部端口均随机。 */
    static final class RegistryFixture {

        private static ConfigurableApplicationContext context;
        private static int externalPort;
        private static int internalPort;

        private RegistryFixture() {}

        static synchronized int externalPort() {
            start();
            return externalPort;
        }

        static synchronized int internalPort() {
            start();
            return internalPort;
        }

        private static void start() {
            if (context != null) {
                return;
            }
            try {
                internalPort = freePort();
                String snapshotPath = Files.createTempDirectory("artemis-registry-it")
                        .resolve("snapshot.bin")
                        .toString();
                // 测试 classpath 上 management-server 的 application.properties 会先于 registry 的
                // 同名资源被加载；用显式参数钉住 registry 相关配置（端口/应用名/readiness 组）
                context = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                        .run(
                                "--spring.application.name=artemis-registry",
                                "--spring.grpc.server.port=0",
                                "--server.port=0",
                                "--management.server.port=0",
                                "--artemis.registry.internal.port=" + internalPort,
                                "--management.endpoint.health.group.readiness.include=readinessState,registryReadiness",
                                "--artemis.registry.snapshot.path=" + snapshotPath);
                // 主 server lifecycle 名随传输实现而定（netty/shaded），按名称排除内部 peer 后取
                externalPort = context.getBeansOfType(GrpcServerLifecycle.class).entrySet().stream()
                        .filter(entry -> !entry.getKey().equals("peerGrpcServerLifecycle"))
                        .findFirst()
                        .orElseThrow()
                        .getValue()
                        .getPort();
                // 内部端口为固定预分配值；这里仅校验实际绑定与配置一致
                int bound = context.getBeansOfType(GrpcServerLifecycle.class)
                        .get("peerGrpcServerLifecycle")
                        .getPort();
                if (bound != internalPort) {
                    throw new IllegalStateException(
                            "peer port mismatch: configured " + internalPort + ", bound " + bound);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot start registry fixture", e);
            }
        }

        private static int freePort() {
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("cannot allocate free port", e);
            }
        }

        static synchronized void stop() {
            if (context != null) {
                context.close();
                context = null;
                externalPort = 0;
                internalPort = 0;
            }
        }
    }
}
