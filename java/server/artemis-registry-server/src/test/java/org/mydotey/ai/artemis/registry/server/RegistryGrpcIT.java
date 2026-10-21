package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
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
 * 服务级集成测试：进程内起真实 registry 服务（Spring Boot 官方 gRPC starter，随机端口），
 * 经真实调用链路（wire 序列化 + mapper + gRPC 绑定 + 租约调度）驱动端到端用例。
 * 测试持有注入的 MutableClock 与事件收集器做确定性断言；调度触发的过期以测试侧有界轮询观察。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class RegistryGrpcIT {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final org.mydotey.ai.artemis.registry.domain.ServiceKey DOMAIN_KEY =
            new org.mydotey.ai.artemis.registry.domain.ServiceKey(NAMESPACE, SERVICE);

    @Autowired
    private Map<String, GrpcServerLifecycle> lifecycles;

    private int port;

    @Autowired
    private RegistryService registryService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private List<InstanceChange> events;

    @Autowired
    private SelfProtection selfProtection;

    private ManagedChannel channel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub blocking;

    @BeforeEach
    void setUp() {
        port = TestGrpcPorts.external(lifecycles);
        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        blocking = RegistryServiceGrpc.newBlockingStub(channel).withInterceptors(AuthClients.authenticated());
        // context 跨用例缓存：清理共享单例（时钟归零、摘除残留实例、清空事件、清空续约统计）保证用例独立
        clock.reset();
        selfProtection.reset();
        registryService.deregister(new org.mydotey.ai.artemis.registry.domain.DeregisterRequest(java.util.List.of(
                new org.mydotey.ai.artemis.registry.domain.InstanceKey(DOMAIN_KEY, "i1"),
                new org.mydotey.ai.artemis.registry.domain.InstanceKey(DOMAIN_KEY, "i2"))));
        events.clear();
    }

    @AfterEach
    void tearDown() {
        channel.shutdown();
    }

    @Test
    void should_register_then_observe_when_serving_grpc() {
        RegisterResponse response = blocking.register(registerRequest("i1"));

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        TestAwait.await(
                "registered instance visible in snapshot",
                () -> registryService
                        .getInstances(DOMAIN_KEY)
                        .map(services -> services.instances().size() == 1)
                        .orElse(false));
        assertThat(events).anySatisfy(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.NEW);
            assertThat(change.instance().instanceId()).isEqualTo("i1");
        });
    }

    @Test
    void should_heartbeat_renew_and_upsert_when_grpc_bidi_stream() throws Exception {
        blocking.register(registerRequest("i1"));
        TestAwait.await("registered", () -> instanceCount() == 1);

        // bidi 流上发两条心跳：i1 续约 + i2 缺席补注册
        List<HeartbeatResponse> responses =
                Heartbeats.collect(channel, heartbeatRequest(1L, "i1"), heartbeatRequest(2L, "i2"));
        assertThat(responses)
                .extracting(response -> response.getData().getSeq())
                .containsExactly(1L, 2L);

        // 缺席补注册生效
        TestAwait.await("absent instance registered by heartbeat", () -> instanceCount() == 2);

        // 续约推后过期时刻（TTL 1000ms）：t=600 再心跳（续约点推后到 600），
        // t=1100 距续约 500 < 1000 仍在（若续约未生效，距注册 1100 >= 1000 即摘——有区分度）；
        // t=1610 距续约 1010 >= 1000 被摘
        clock.advance(Duration.ofMillis(600));
        assertThat(Heartbeats.collect(channel, heartbeatRequest(3L, "i1", "i2")))
                .hasSize(1);
        clock.advance(Duration.ofMillis(500));
        assertThat(instanceCount()).isEqualTo(2);
        clock.advance(Duration.ofMillis(510));
        TestAwait.await("instances evicted after ttl from renewal", () -> instanceCount() == 0);
    }

    @Test
    void should_deregister_idempotently_over_grpc() {
        blocking.register(registerRequest("i1"));
        TestAwait.await("registered", () -> instanceCount() == 1);
        events.clear();

        DeregisterResponse miss = blocking.deregister(deregisterRequest("ghost"));
        assertThat(miss.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(events).noneMatch(change -> change.type() == ChangeType.DELETE);

        DeregisterResponse hit = blocking.deregister(deregisterRequest("i1"));
        assertThat(hit.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        TestAwait.await("deregistered", () -> instanceCount() == 0);
        assertThat(events).anySatisfy(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.DELETE);
            assertThat(change.instance().instanceId()).isEqualTo("i1");
        });
    }

    @Test
    void should_report_invalid_argument_over_grpc_when_instance_id_empty() {
        RegisterResponse response = blocking.register(registerRequest("", "i2"));

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_FAIL);
        assertThat(response.getStatus().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getData().getServicesList())
                .singleElement()
                .satisfies(group -> assertThat(group.getFailedList())
                        .singleElement()
                        .satisfies(error -> {
                            assertThat(error.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
                            assertThat(error.getInstance().getInstanceId()).isEmpty();
                        }));
        // 其余实例照常生效
        TestAwait.await("valid instance still effective", () -> instanceCount() == 1);
    }

    @Test
    void should_evict_and_observe_when_ttl_elapses() {
        blocking.register(registerRequest("i1"));
        TestAwait.await("registered", () -> instanceCount() == 1);
        events.clear();

        clock.advance(Duration.ofMillis(1500)); // 超过 TTL 1000ms

        TestAwait.await("expired instance evicted", () -> instanceCount() == 0);
        // 原产品继承（phase-2）：TTL 清理本地删除、零事件（DELETE 不扇出，各节点独立过期）
        assertThat(events).isEmpty();
    }

    @Test
    void should_expose_server_time_when_heartbeat_over_grpc() throws Exception {
        clock.advance(Duration.ofMillis(1234));

        // bidi 流只有 async stub（blocking stub 不生成流方法）
        List<HeartbeatResponse> responses = Heartbeats.collect(channel, heartbeatRequest(5L, "i1"));

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getData().getServerTimeMs()).isEqualTo(1234L);
            assertThat(response.getData().getSeq()).isEqualTo(5L);
        });
    }

    // ---- 测试装配（端口注入：可控时钟 + 短 TTL + 事件收集）----

    @TestConfiguration(proxyBeanMethods = false)
    static class GrpcTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            // MutableClock 同源实现壁挂/单调两端口（D14）：Clock 与 MonotonicClock 注入点均解析至此
            return new MutableClock();
        }

        @Bean
        @Primary
        StringProperties testProperties() {
            return TestProperties.of(Map.of(
                            "artemis.registry.lease.ttlMs",
                            "1000",
                            "artemis.registry.internal.port",
                            String.valueOf(PEER_PORT)))
                    .properties();
        }

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            // 隔离文件系统：不读写工作目录快照（重启回放语义由 SnapshotRestoreIT 专测）
            return new InMemorySnapshotStore();
        }

        @Bean
        @Primary
        ChangeListener changeCollector(List<InstanceChange> sink) {
            return sink::add;
        }

        @Bean
        List<InstanceChange> changeSink() {
            return new CopyOnWriteArrayList<>();
        }
    }

    // ---- 设施 ----

    private int instanceCount() {
        return registryService
                .getInstances(DOMAIN_KEY)
                .map(services -> services.instances().size())
                .orElse(0);
    }

    private static RegisterRequest registerRequest(String... instanceIds) {
        return RegisterRequest.newBuilder()
                .addServices(serviceInstances(instanceIds))
                .build();
    }

    private static HeartbeatRequest heartbeatRequest(long seq, String... instanceIds) {
        return HeartbeatRequest.newBuilder()
                .setSeq(seq)
                .addServices(serviceInstances(instanceIds))
                .build();
    }

    private static DeregisterRequest deregisterRequest(String instanceId) {
        return DeregisterRequest.newBuilder()
                .addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                        .setService(serviceKey())
                        .setInstanceId(instanceId))
                .build();
    }

    private static ServiceInstances.Builder serviceInstances(String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(serviceKey());
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        return services;
    }

    private static ServiceKey.Builder serviceKey() {
        return ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE);
    }
}
