package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.List;
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
import org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 服务级集成测试：真实 gRPC 接入 + 真实注册链路后，经 RegistryQueryService（v0.2 起在
 * 内部 peer 端口）驱动查询（服务列表/实例快照/租约与保护统计/配置脱敏/校验）。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class RegistryQueryIT {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    private static final String NAMESPACE = "query-it";
    private static final String SERVICE = "order-service";
    private static final String PROTECTION_NAMESPACE = "query-protection";

    @Autowired
    private Map<String, GrpcServerLifecycle> lifecycles;

    @Autowired
    private MutableClock clock;

    @Autowired
    private SelfProtection selfProtection;

    @Autowired
    private RegistryService registryService;

    private ManagedChannel externalChannel;
    private ManagedChannel internalChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub query;

    @BeforeEach
    void setUp() {
        externalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.external(lifecycles))
                .usePlaintext()
                .build();
        internalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.internal(lifecycles))
                .usePlaintext()
                .build();
        registry = RegistryServiceGrpc.newBlockingStub(externalChannel).withInterceptors(AuthClients.authenticated());
        // 内部认证默认关闭：查询走内部端口且无需凭证
        query = RegistryQueryServiceGrpc.newBlockingStub(internalChannel);
        clock.reset();
        selfProtection.reset();
        registry.deregister(DeregisterRequest.newBuilder()
                .addInstances(instanceKey("i1"))
                .addInstances(instanceKey("i2"))
                .build());
        cleanupProtectionInstances();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        cleanupProtectionInstances();
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
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
    void should_report_per_service_protection_when_querying() throws Exception {
        ServiceKey a = protectionKey("svc-a");
        ServiceKey b = protectionKey("svc-b");
        ServiceKey keepAlive = protectionKey("keep-alive");
        heartbeatMany(a, List.of("a1", "a2"), 1L); // 峰值 2，达到 minCount=2
        heartbeatMany(b, List.of("b1"), 1L); // 峰值 1，未达服务级门槛

        // 窗口 1s 滑出、TTL 1s 到期；高续约 keep-alive 保持节点级健康
        clock.advance(Duration.ofMillis(1_100));
        heartbeatMany(
                keepAlive,
                java.util.stream.IntStream.range(0, 100).mapToObj(i -> "k" + i).toList(),
                2L);
        registryService.evictExpired();

        var response = query.getSelfProtectionStatus(GetSelfProtectionStatusRequest.newBuilder()
                .setNamespace(PROTECTION_NAMESPACE)
                .build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getProtectingServices()).isEqualTo(1);
        assertThat(response.getData().getServicesList()).singleElement().satisfies(item -> {
            assertThat(item.getService().getService()).isEqualTo("svc-a");
            assertThat(item.getNodeFallback()).isFalse();
            assertThat(item.getProtecting()).isTrue();
        });
        assertThat(response.getData().getNode().getProtecting()).isFalse();

        // A 服务级保护保留，B 未达门槛且节点健康 → 正常过期
        assertThat(instances(a)).hasSize(2);
        assertThat(instances(b)).isEmpty();
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

    private static ServiceKey protectionKey(String service) {
        return ServiceKey.newBuilder()
                .setNamespace(PROTECTION_NAMESPACE)
                .setService(service)
                .build();
    }

    private void heartbeatMany(ServiceKey key, List<String> instanceIds, long seq) throws InterruptedException {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(key);
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        Heartbeats.collect(
                externalChannel,
                HeartbeatRequest.newBuilder().addServices(services).setSeq(seq).build());
    }

    /** 清理保护用例命名空间：该 namespace 的租约续约时间可能因 clock.reset() 落在未来，不能等 TTL。 */
    private void cleanupProtectionInstances() {
        deregister(protectionKey("svc-a"), "a1", "a2");
        deregister(protectionKey("svc-b"), "b1");
        deregister(
                protectionKey("keep-alive"),
                java.util.stream.IntStream.range(0, 100).mapToObj(i -> "k" + i).toArray(String[]::new));
    }

    private void deregister(ServiceKey key, String... instanceIds) {
        DeregisterRequest.Builder request = DeregisterRequest.newBuilder();
        for (String instanceId : instanceIds) {
            request.addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                    .setService(key)
                    .setInstanceId(instanceId));
        }
        registry.deregister(request.build());
    }

    private List<Instance> instances(ServiceKey key) {
        return query.getServiceInstances(
                        GetServiceInstancesRequest.newBuilder().setKey(key).build())
                .getData()
                .getInstancesList();
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
            return TestProperties.of(Map.of(
                            "artemis.registry.lease.ttlMs",
                            "1000",
                            "artemis.registry.internal.port",
                            String.valueOf(PEER_PORT),
                            "artemis.registry.selfProtection.windowSeconds",
                            "1",
                            "artemis.registry.selfProtection.minCount",
                            "2",
                            "artemis.registry.selfProtection.maxCountResetMs",
                            "60000"))
                    .properties();
        }

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            return new InMemorySnapshotStore();
        }
    }
}
