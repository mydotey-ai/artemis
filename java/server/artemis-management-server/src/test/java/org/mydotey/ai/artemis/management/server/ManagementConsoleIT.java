package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * console 只读版服务级集成测试：同 JVM 真实 registry + 真实 management（随机端口），
 * 经真实 gRPC 注册与真实 HTTP + Basic 认证驱动 console 页面可达性与搜索/分页 API。
 * namespace 按用例隔离（无跨用例清理），服务名取 console 独有前缀与共用 fixture 的其他 IT 天然隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementConsoleIT extends ManagementHttpIT {

    private static final String LIST_NAMESPACE = "console-list-it";
    private static final String DETAIL_NAMESPACE = "console-detail-it";

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
    void should_serve_console_page_when_root_requested() throws Exception {
        HttpResponse<String> unauthenticated =
                send(HttpRequest.newBuilder(uri("/")).GET(), false);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/")).GET(), true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).contains("text/html");
        assertThat(page.body()).contains("Artemis Console");
    }

    @Test
    void should_filter_and_page_services_over_http_when_console_api_queried() throws Exception {
        registerService(LIST_NAMESPACE, "console-order-web", "i1");
        registerService(LIST_NAMESPACE, "console-user-web", "i1");
        registerService(LIST_NAMESPACE, "console-billing-job", "i1");

        JsonNode first =
                json(get("/admin/v1/services?namespace=" + LIST_NAMESPACE + "&keyword=CONSOLE&offset=0&limit=2"));
        assertThat(first.get("total").asInt()).isEqualTo(3); // keyword 大小写不敏感
        assertThat(first.get("services")).hasSize(2);
        assertThat(first.get("services").get(0).get("key").get("service").asText())
                .isEqualTo("console-billing-job");

        JsonNode second =
                json(get("/admin/v1/services?namespace=" + LIST_NAMESPACE + "&keyword=CONSOLE&offset=2&limit=2"));
        assertThat(second.get("total").asInt()).isEqualTo(3);
        assertThat(second.get("services")).hasSize(1);
        assertThat(second.get("services").get(0).get("key").get("service").asText())
                .isEqualTo("console-user-web");

        JsonNode missed =
                json(get("/admin/v1/services?namespace=" + LIST_NAMESPACE + "&keyword=billing&offset=0&limit=10"));
        assertThat(missed.get("total").asInt()).isEqualTo(1); // 模糊命中子串
        assertThat(missed.get("services").get(0).get("key").get("service").asText())
                .isEqualTo("console-billing-job");
    }

    @Test
    void should_page_instances_over_http_when_detail_queried() throws Exception {
        registerService(DETAIL_NAMESPACE, "console-goods-web", "i1", "i2", "i3");
        post(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + DETAIL_NAMESPACE
                        + "\",\"service\":\"console-goods-web\",\"instanceId\":\"i1\",\"reason\":\"it\"}");

        JsonNode all = json(get(
                "/admin/v1/services/" + DETAIL_NAMESPACE + "/console-goods-web?includeEjected=true&offset=1&limit=1"));
        assertThat(all.get("instanceCount").asInt()).isEqualTo(3); // counts 始终全量
        assertThat(all.get("ejectedCount").asInt()).isEqualTo(1);
        assertThat(all.get("instances")).hasSize(1);
        assertThat(all.get("instances").get(0).get("instanceId").asText()).isEqualTo("i2");

        JsonNode effective = json(get(
                "/admin/v1/services/" + DETAIL_NAMESPACE + "/console-goods-web?includeEjected=false&offset=0&limit=1"));
        assertThat(effective.get("effectiveInstanceCount").asInt()).isEqualTo(2);
        assertThat(effective.get("instances")).hasSize(1);
        assertThat(effective.get("instances").get(0).get("instanceId").asText()).isEqualTo("i2"); // 摘除先过滤后切片
    }

    private void registerService(String namespace, String service, String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder()
                .setKey(ServiceKey.newBuilder()
                        .setNamespace(namespace)
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
