package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
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
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 服务级集成测试：同 JVM 起真实 registry-server（随机端口）+ 真实 management-server（随机 HTTP 端口），
 * 经真实 gRPC 注册/查询与真实 HTTP + Basic 认证驱动管理面有效视图与状态 API。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementAdminIT extends ManagementHttpIT {

    private static final String NAMESPACE = "mgmt-it";
    private static final String SERVICE = "order-service";

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
        post("/admin/v1/ejections/recover", ejectBody("i1", null));
        post("/admin/v1/ejections/recover", ejectBody("i2", null));
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

        assertThat(post("/admin/v1/ejections", ejectBody("i1", "maintenance")).statusCode())
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
        post("/admin/v1/ejections", ejectBody("i1", "maintenance"));

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
        post("/admin/v1/ejections", ejectBody("i1", "maintenance"));

        var response = registryQuery.getServiceInstances(
                GetServiceInstancesRequest.newBuilder().setKey(serviceKey()).build());

        assertThat(response.getData().getInstancesList())
                .extracting(Instance::getInstanceId)
                .containsExactlyInAnyOrder("i1", "i2");
    }

    @Test
    void should_restore_effective_view_when_recovered() throws Exception {
        register("i1", "i2");
        post("/admin/v1/ejections", ejectBody("i1", "maintenance"));

        assertThat(post("/admin/v1/ejections/recover", ejectBody("i1", null)).statusCode())
                .isEqualTo(204);

        JsonNode detail = json(get(detailPath(), true));
        assertThat(detail.get("ejectedCount").asInt()).isZero();
        assertThat(detail.get("instances").size()).isEqualTo(2);
    }

    @Test
    void should_stack_ejections_and_list_records_over_http() throws Exception {
        register("i1");

        JsonNode first = json(post("/admin/v1/ejections", ejectBody("i1", "first")));
        JsonNode second = json(post("/admin/v1/ejections", ejectBody("i1", "second")));

        // 叠加：两条记录各留痕（操作记录即状态），active 代表集一条（最早未对消）
        assertThat(second.get("reason").asText()).isEqualTo("second");
        assertThat(second.get("sequence").asLong())
                .isGreaterThan(first.get("sequence").asLong());
        JsonNode active = json(get("/admin/v1/ejections?activeOnly=true&scope=INSTANCE", true))
                .get("records");
        assertThat(active.size()).isEqualTo(1);
        assertThat(active.get(0).get("instanceId").asText()).isEqualTo("i1");

        // 部分对消：一次 recover 后仍 active；完全恢复后清空；多余 recover 幂等 204
        assertThat(post("/admin/v1/ejections/recover", ejectBody("i1", null)).statusCode())
                .isEqualTo(204);
        assertThat(json(get("/admin/v1/ejections?activeOnly=true&scope=INSTANCE", true))
                        .get("records")
                        .size())
                .isEqualTo(1);
        assertThat(post("/admin/v1/ejections/recover", ejectBody("i1", null)).statusCode())
                .isEqualTo(204);
        assertThat(post("/admin/v1/ejections/recover", ejectBody("i1", null)).statusCode())
                .isEqualTo(204);
        assertThat(json(get("/admin/v1/ejections?activeOnly=true", true))
                        .get("records")
                        .size())
                .isZero();
    }

    @Test
    void should_manage_route_rules_when_crud_called() throws Exception {
        String body = """
                {"namespace":"%s","service":"%s","name":"beta","targets":[
                {"selector":{"group":"beta"},"weight":20,"unreleasedWeight":80},
                {"selector":{},"weight":0,"unreleasedWeight":20}]}
                """.formatted(NAMESPACE, SERVICE);

        // 201 创建 + 视图字段（status 缺省 ACTIVE、operator、两段权重双列、兜底目标）
        HttpResponse<String> created = post("/admin/v1/route-rules", body);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode view = json(created);
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("operator").asText()).isEqualTo("artemis");
        assertThat(view.get("targets").size()).isEqualTo(2);
        assertThat(view.get("targets").get(0).get("unreleasedWeight").asInt()).isEqualTo(80);
        assertThat(view.get("createdAt")).isNotNull();
        assertThat(view.get("updatedAt")).isNotNull();

        // 409 重名 / 400 校验（name 非法字符） / 404 缺失
        assertThat(post("/admin/v1/route-rules", body).statusCode()).isEqualTo(409);
        assertThat(post("/admin/v1/route-rules", body.replace("\"beta\"", "\"beta rule\""))
                        .statusCode())
                .isEqualTo(400);
        assertThat(get("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/missing", true)
                        .statusCode())
                .isEqualTo(404);

        // PUT 全量替换（status INACTIVE）→ 200
        HttpResponse<String> updated = send(
                HttpRequest.newBuilder(uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"namespace":"%s","service":"%s","name":"beta","status":"INACTIVE",
                                "targets":[{"selector":{"group":"beta"},"weight":0,"unreleasedWeight":100}]}
                                """.formatted(NAMESPACE, SERVICE))),
                true);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).get("status").asText()).isEqualTo("INACTIVE");
        // body 键与路径不一致（防静默改键）→ 400；body 缺 name → 400（非 NPE 500，review #1 锁定）
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(
                                                body.replace("\"name\":\"beta\"", "\"name\":\"gamma\""))),
                                true)
                        .statusCode())
                .isEqualTo(400);
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .PUT(HttpRequest.BodyPublishers.ofString(
                                                body.replace(",\"name\":\"beta\"", ""))),
                                true)
                        .statusCode())
                .isEqualTo(400);

        // DELETE 幂等 204；删除后 GET 404
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .DELETE(),
                                true)
                        .statusCode())
                .isEqualTo(204);
        assertThat(send(
                                HttpRequest.newBuilder(
                                                uri("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta"))
                                        .DELETE(),
                                true)
                        .statusCode())
                .isEqualTo(204);
        assertThat(get("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta", true)
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void should_return_400_when_ejection_request_invalid() throws Exception {
        // scope 非法
        assertThat(post("/admin/v1/ejections", "{\"scope\":\"HOST\",\"value\":\"10.1.1.1\"}")
                        .statusCode())
                .isEqualTo(400);
        // INSTANCE 级缺 service/instanceId
        assertThat(post("/admin/v1/ejections", "{\"namespace\":\"" + NAMESPACE + "\"}")
                        .statusCode())
                .isEqualTo(400);
        // selector 级缺 value
        assertThat(post("/admin/v1/ejections", "{\"scope\":\"ZONE\"}").statusCode())
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
    void should_expose_self_protection_status_when_status_api_called() throws Exception {
        register("i1");
        heartbeat("i1");

        // includeNormal=true：正常服务也返回（服务窗口峰值 1 < minCount → 节点级兜底）
        JsonNode all = json(get("/admin/v1/status/protection?namespace=" + NAMESPACE + "&includeNormal=true", true));
        JsonNode service = StreamSupport.stream(all.get("services").spliterator(), false)
                .filter(item ->
                        SERVICE.equals(item.get("service").get("service").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(service.get("nodeFallback").asBoolean()).isTrue();
        assertThat(service.get("protecting").asBoolean()).isFalse();
        assertThat(all.get("node")).isNotNull();

        // 默认仅保护中服务：该服务正常 → 空列表
        JsonNode defaultOnly = json(get("/admin/v1/status/protection?namespace=" + NAMESPACE, true));
        assertThat(defaultOnly.get("services").size()).isZero();

        // 指定 namespace + service 时总是返回
        JsonNode filtered =
                json(get("/admin/v1/status/protection?namespace=" + NAMESPACE + "&service=" + SERVICE, true));
        assertThat(filtered.get("services").size()).isEqualTo(1);
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

    private void heartbeat(String... instanceIds) throws InterruptedException {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(serviceKey());
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        CountDownLatch acked = new CountDownLatch(1);
        StreamObserver<HeartbeatRequest> stream = RegistryServiceGrpc.newStub(externalChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .heartbeat(new StreamObserver<HeartbeatResponse>() {
                    @Override
                    public void onNext(HeartbeatResponse response) {
                        acked.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        acked.countDown();
                    }

                    @Override
                    public void onCompleted() {}
                });
        stream.onNext(
                HeartbeatRequest.newBuilder().addServices(services).setSeq(1).build());
        stream.onCompleted();
        assertThat(acked.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static String detailPath() {
        return "/admin/v1/services/" + NAMESPACE + "/" + SERVICE;
    }

    /** 统一摘除/恢复请求体（INSTANCE 级；reason 为 null 时省略）。 */
    private static String ejectBody(String instanceId, String reason) {
        StringBuilder body = new StringBuilder("{\"scope\":\"INSTANCE\",\"namespace\":\"")
                .append(NAMESPACE)
                .append("\",\"service\":\"")
                .append(SERVICE)
                .append("\",\"instanceId\":\"")
                .append(instanceId)
                .append("\"");
        if (reason != null) {
            body.append(",\"reason\":\"").append(reason).append("\"");
        }
        return body.append("}").toString();
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
}
