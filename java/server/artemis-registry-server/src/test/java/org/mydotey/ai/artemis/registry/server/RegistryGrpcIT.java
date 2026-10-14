package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 服务级集成测试：进程内起真实 registry 服务（Spring Boot 官方 gRPC starter，随机端口），
 * 经真实调用链路（wire 序列化 + mapper + gRPC 绑定 + 租约调度）驱动端到端用例。
 * 测试持有注入的 MutableClock 与事件收集器做确定性断言；调度触发的过期以测试侧有界轮询观察。
 */
@SpringBootTest(properties = "spring.grpc.server.port=0")
class RegistryGrpcIT {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final org.mydotey.ai.artemis.registry.domain.ServiceKey DOMAIN_KEY =
            new org.mydotey.ai.artemis.registry.domain.ServiceKey(NAMESPACE, SERVICE);

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private RegistryService registryService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private List<InstanceChange> events;

    private ManagedChannel channel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub blocking;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        blocking = RegistryServiceGrpc.newBlockingStub(channel);
        // context 跨用例缓存：清理共享单例（时钟归零、摘除残留实例、清空事件）保证用例独立
        clock.reset();
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
        CountDownLatch acked = new CountDownLatch(2);
        List<HeartbeatResponse> responses = new CopyOnWriteArrayList<>();
        StreamObserver<HeartbeatRequest> requests = RegistryServiceGrpc.newStub(channel)
                .heartbeat(new StreamObserver<>() {
                    @Override
                    public void onNext(HeartbeatResponse response) {
                        responses.add(response);
                        acked.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {}

                    @Override
                    public void onCompleted() {}
                });
        requests.onNext(heartbeatRequest(1L, "i1"));
        requests.onNext(heartbeatRequest(2L, "i2"));
        requests.onCompleted();
        assertThat(acked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(responses)
                .extracting(response -> response.getData().getSeq())
                .containsExactly(1L, 2L);

        // 缺席补注册生效
        TestAwait.await("absent instance registered by heartbeat", () -> instanceCount() == 2);

        // 续约推后过期时刻（TTL 100ms）：t=60 再心跳（续约点推后到 60），
        // t=110 距续约 50 < 100 仍在（若续约未生效，距注册 110 >= 100 即摘——有区分度）；
        // t=161 距续约 101 >= 100 被摘
        clock.advance(Duration.ofMillis(60));
        CountDownLatch renewed = new CountDownLatch(1);
        StreamObserver<HeartbeatRequest> renewalRequests = RegistryServiceGrpc.newStub(channel)
                .heartbeat(new StreamObserver<>() {
                    @Override
                    public void onNext(HeartbeatResponse response) {
                        renewed.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {}

                    @Override
                    public void onCompleted() {}
                });
        renewalRequests.onNext(heartbeatRequest(3L, "i1", "i2"));
        renewalRequests.onCompleted();
        assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofMillis(50));
        assertThat(instanceCount()).isEqualTo(2);
        clock.advance(Duration.ofMillis(51));
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

        clock.advance(Duration.ofMillis(150)); // 超过 TTL 100ms

        TestAwait.await("expired instance evicted", () -> instanceCount() == 0);
        assertThat(events).anySatisfy(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.DELETE);
            assertThat(change.instance().instanceId()).isEqualTo("i1");
        });
    }

    @Test
    void should_expose_server_time_when_heartbeat_over_grpc() throws Exception {
        clock.advance(Duration.ofMillis(1234));

        // bidi 流只有 async stub（blocking stub 不生成流方法）
        CountDownLatch acked = new CountDownLatch(1);
        List<HeartbeatResponse> responses = new CopyOnWriteArrayList<>();
        StreamObserver<HeartbeatRequest> requests = RegistryServiceGrpc.newStub(channel)
                .heartbeat(new StreamObserver<>() {
                    @Override
                    public void onNext(HeartbeatResponse response) {
                        responses.add(response);
                        acked.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {}

                    @Override
                    public void onCompleted() {}
                });
        requests.onNext(heartbeatRequest(5L, "i1"));
        requests.onCompleted();
        assertThat(acked.await(5, TimeUnit.SECONDS)).isTrue();

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
        LeaseTtlSource shortLeaseTtl() {
            return () -> Duration.ofMillis(100);
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
