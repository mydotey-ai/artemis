package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * phase-4 端到端验收：认证拒绝/放行（gRPC + HTTP）、metrics 抓取、readiness、trace 观测产出。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.grpc.server.port=0", "management.server.port=0"})
class AuthObservabilityIT {

    @LocalGrpcServerPort
    private int grpcPort;

    @LocalServerPort
    private int httpPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObservationRegistry observationRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_reject_grpc_call_when_no_credentials() {
        assertThatThrownBy(() -> RegistryServiceGrpc.newBlockingStub(channel).register(registerRequest()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

        assertThat(RegistryServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(AuthClients.authenticated())
                        .register(registerRequest())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    @Test
    void should_reject_external_http_when_no_credentials() throws Exception {
        assertThat(get(httpPort, "/api/anything").statusCode()).isEqualTo(401);

        int authenticatedStatus = get(
                        httpPort, "/api/anything", BasicAuth.headerValue(AuthClients.USERNAME, AuthClients.PASSWORD))
                .statusCode();
        assertThat(authenticatedStatus).isNotEqualTo(401);
    }

    @Test
    void should_serve_readyz_without_credentials() throws Exception {
        HttpResponse<String> response = get(httpPort, "/readyz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ready");
    }

    @Test
    void should_scrape_prometheus_metrics() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("artemis_registry_instances");
        assertThat(response.body()).contains("artemis_registry_self_protection");
    }

    @Test
    void should_expose_readiness_health_endpoint() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void should_produce_observation_for_grpc_call() {
        List<String> names = new CopyOnWriteArrayList<>();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                names.add(context.getName());
            }
        });

        RegistryServiceGrpc.newBlockingStub(channel)
                .withInterceptors(AuthClients.authenticated())
                .register(registerRequest());

        assertThat(names).anyMatch(name -> name.contains("grpc"));
    }

    private HttpResponse<String> get(int port, String path, String... authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (authorization.length > 0) {
            builder.header("Authorization", authorization[0]);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static RegisterRequest registerRequest() {
        return RegisterRequest.newBuilder()
                .addServices(ServiceInstances.newBuilder()
                        .setKey(ServiceKey.newBuilder().setNamespace("default").setService("order-service"))
                        .addInstances(Instance.newBuilder()
                                .setInstanceId("i1")
                                .setHost("10.0.0.1")
                                .setPort(8080)))
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            return new InMemorySnapshotStore();
        }

        @Bean
        @Primary
        StringProperties testProperties() {
            // 隔离宿主环境变量/工作目录配置文件，认证断言只依赖默认值
            return TestProperties.empty();
        }
    }
}
