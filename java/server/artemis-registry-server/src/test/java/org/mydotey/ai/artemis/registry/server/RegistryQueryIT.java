package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 服务级集成测试：真实 gRPC 接入 + 真实注册链路后，经 RegistryQueryService 驱动查询
 * （服务列表/实例快照/租约与保护统计/配置脱敏/认证与校验）。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class RegistryQueryIT {

    private static final String NAMESPACE = "query-it";
    private static final String SERVICE = "order-service";

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    private ManagedChannel channel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub query;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        registry = RegistryServiceGrpc.newBlockingStub(channel).withInterceptors(AuthClients.authenticated());
        query = RegistryQueryServiceGrpc.newBlockingStub(channel).withInterceptors(AuthClients.authenticated());
        clock.reset();
        registry.deregister(DeregisterRequest.newBuilder()
                .addInstances(instanceKey("i1"))
                .addInstances(instanceKey("i2"))
                .build());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_reject_query_when_unauthenticated() {
        RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub anonymous =
                RegistryQueryServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> anonymous.listServices(ListServicesRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void should_list_services_when_instances_registered() {
        registry.register(registerRequest("i1", "i2"));

        var response = query.listServices(
                ListServicesRequest.newBuilder().setNamespace(NAMESPACE).build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getServicesList()).singleElement().satisfies(summary -> {
            assertThat(summary.getKey().getNamespace()).isEqualTo(NAMESPACE);
            assertThat(summary.getKey().getService()).isEqualTo(SERVICE);
            assertThat(summary.getInstanceCount()).isEqualTo(2);
        });
    }

    @Test
    void should_return_instances_when_query_service() {
        registry.register(registerRequest("i1"));

        GetServiceInstancesResponse response = query.getServiceInstances(
                GetServiceInstancesRequest.newBuilder().setKey(serviceKey()).build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getInstancesList()).singleElement().satisfies(instance -> {
            assertThat(instance.getInstanceId()).isEqualTo("i1");
            assertThat(instance.getHost()).isEqualTo("10.0.0.1");
            assertThat(instance.getPort()).isEqualTo(8080);
        });
    }

    @Test
    void should_return_empty_when_service_not_registered() {
        GetServiceInstancesResponse response = query.getServiceInstances(
                GetServiceInstancesRequest.newBuilder().setKey(serviceKey()).build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getInstancesList()).isEmpty();
    }

    @Test
    void should_report_lease_details_and_protection() {
        registry.register(registerRequest("i1"));
        clock.advance(Duration.ofMillis(400));

        var response = query.getLeaseStatus(GetLeaseStatusRequest.getDefaultInstance());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getLeasesList())
                .filteredOn(lease -> lease.getService().getNamespace().equals(NAMESPACE))
                .singleElement()
                .satisfies(lease -> {
                    assertThat(lease.getInstanceId()).isEqualTo("i1");
                    assertThat(lease.getAgeMs()).isEqualTo(400);
                    assertThat(lease.getRemainingTtlMs()).isEqualTo(600);
                });
        assertThat(response.getData().hasProtection()).isTrue();
        assertThat(response.getData().getTruncated()).isFalse();
    }

    @Test
    void should_mask_password_when_query_config_status() {
        var response = query.getConfigStatus(GetConfigStatusRequest.getDefaultInstance());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getPropertiesList())
                .filteredOn(property -> property.getKey().equals("artemis.registry.auth.password"))
                .singleElement()
                .satisfies(property -> {
                    assertThat(property.getValue()).isEqualTo("******");
                    assertThat(property.getDefaultValue()).isEqualTo("******");
                });
    }

    @Test
    void should_return_invalid_argument_when_service_key_blank() {
        GetServiceInstancesResponse response = query.getServiceInstances(GetServiceInstancesRequest.newBuilder()
                .setKey(ServiceKey.newBuilder().setNamespace(" ").setService(""))
                .build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_FAIL);
        assertThat(response.getStatus().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    }

    private static org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest registerRequest(String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(serviceKey());
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        return org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                .addServices(services)
                .build();
    }

    private static ServiceKey serviceKey() {
        return ServiceKey.newBuilder()
                .setNamespace(NAMESPACE)
                .setService(SERVICE)
                .build();
    }

    private static org.mydotey.ai.artemis.proto.common.v1.InstanceKey instanceKey(String instanceId) {
        return org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                .setService(serviceKey())
                .setInstanceId(instanceId)
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueryTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        StringProperties testProperties() {
            return TestProperties.of(Map.of("artemis.registry.lease.ttlMs", "1000"))
                    .properties();
        }

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            return new InMemorySnapshotStore();
        }
    }
}
