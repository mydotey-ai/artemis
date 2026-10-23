package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeBatch;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeType;
import org.mydotey.ai.artemis.proto.projection.v1.ProjectionServiceGrpc;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;
import org.mydotey.ai.artemis.proto.projection.v1.WatchRequest;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 服务级集成测试：真实 registry（外部 + 内部 peer 端口）驱动 projection.v1——
 * bootstrap 全量与分片、watch 变更/清理语义、断流重连 re-bootstrap。
 * readiness 契约由 {@code ProjectionGrpcServiceTest} 覆盖（真服务未就绪不可控）。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class ProjectionSourceIT {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    private static final String NAMESPACE = "projection-it";
    private static final String SERVICE = "order-service";
    private static final String SHARD_NAMESPACE = "projection-shard";
    private static final int SHARD_SERVICE_COUNT = 2_000;
    private static final int SHARD_BYTES = 64 * 1024;

    @Autowired
    private Map<String, GrpcServerLifecycle> lifecycles;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RegistryService registryService;

    private ManagedChannel externalChannel;
    private ManagedChannel internalChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private ProjectionServiceGrpc.ProjectionServiceBlockingStub projection;
    private ProjectionServiceGrpc.ProjectionServiceStub projectionAsync;

    @BeforeEach
    void setUp() {
        externalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.external(lifecycles))
                .usePlaintext()
                .build();
        internalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.internal(lifecycles))
                .usePlaintext()
                .build();
        registry = RegistryServiceGrpc.newBlockingStub(externalChannel).withInterceptors(AuthClients.authenticated());
        // 内部认证默认关闭：投影走内部端口且无需凭证
        projection = ProjectionServiceGrpc.newBlockingStub(internalChannel);
        projectionAsync = ProjectionServiceGrpc.newStub(internalChannel);
        clock.reset();
        deregister("i1", "i2");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        deregister("i1", "i2");
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_bootstrap_empty_and_full_snapshot() {
        assertThat(bootstrapServices())
                .noneMatch(service -> service.getKey().getNamespace().equals(NAMESPACE));

        registry.register(registerRequest(NAMESPACE, SERVICE, "i1"));

        assertThat(bootstrapServices())
                .filteredOn(service -> service.getKey().getNamespace().equals(NAMESPACE))
                .singleElement()
                .satisfies(service -> {
                    assertThat(service.getKey().getService()).isEqualTo(SERVICE);
                    assertThat(service.getInstancesList())
                            .extracting(Instance::getInstanceId)
                            .containsExactly("i1");
                });
    }

    @Test
    void should_split_bootstrap_into_multiple_shards() {
        // 单服务序列化 ~50B：2000 个服务跨过 64KB 分片预算 → 至少两片
        String[] services = new String[SHARD_SERVICE_COUNT];
        for (int i = 0; i < SHARD_SERVICE_COUNT; i++) {
            services[i] = "svc-" + i;
        }
        registerMany(SHARD_NAMESPACE, services);

        List<BootstrapResponse> responses = bootstrapResponses();

        assertThat(responses).hasSizeGreaterThan(1);
        assertThat(responses)
                .flatExtracting(response -> response.getData().getServicesList())
                .filteredOn(service -> service.getKey().getNamespace().equals(SHARD_NAMESPACE))
                .hasSize(SHARD_SERVICE_COUNT);
    }

    @Test
    void should_receive_change_batch_on_watch_stream() {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        WatchClient watch = openWatch(events);

        registry.register(registerRequest(NAMESPACE, SERVICE, "i1"));
        registry.deregister(deregisterRequest("i1"));

        // 组批可能合并同实例事件（最终状态流）：只要求末态为 DELETE
        TestAwait.await("delete for i1 delivered", () -> lastType(events, "i1") == ChangeType.CHANGE_TYPE_DELETE);
        assertThat(changes(events, "i1"))
                .extracting(org.mydotey.ai.artemis.proto.projection.v1.InstanceChange::getType)
                .doesNotContain(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        watch.close();
    }

    @Test
    void should_not_receive_renew_on_watch_stream() throws InterruptedException {
        registry.register(registerRequest(NAMESPACE, SERVICE, "i1"));
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        WatchClient watch = openWatch(events);

        // 内容未变的心跳（RENEW）+ 控制事件（新实例 NEW）：RENEW 若被转发必先于控制事件到达
        Heartbeats.collect(externalChannel, heartbeatRequest("i1"), heartbeatRequest("i2"));

        TestAwait.await("control change for i2 delivered", () -> lastType(events, "i2") == ChangeType.CHANGE_TYPE_NEW);
        assertThat(changes(events, "i1")).isEmpty();
        watch.close();
    }

    @Test
    void should_receive_delete_on_watch_stream_when_lease_expired() {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        WatchClient watch = openWatch(events);

        registry.register(registerRequest(NAMESPACE, SERVICE, "i1"));
        TestAwait.await("new for i1 delivered", () -> lastType(events, "i1") == ChangeType.CHANGE_TYPE_NEW);

        clock.advance(Duration.ofMillis(1_100)); // TTL 1000ms
        registryService.evictExpired();

        TestAwait.await(
                "eviction delete for i1 delivered", () -> lastType(events, "i1") == ChangeType.CHANGE_TYPE_DELETE);
        watch.close();
    }

    @Test
    void should_rebootstrap_after_watch_disconnect() {
        registry.register(registerRequest(NAMESPACE, SERVICE, "i1"));
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        WatchClient watch = openWatch(events);
        registry.register(registerRequest(NAMESPACE, SERVICE, "i2"));
        TestAwait.await("new for i2 delivered", () -> lastType(events, "i2") == ChangeType.CHANGE_TYPE_NEW);
        watch.close(); // 断流（模拟网络中断/客户端重连）

        // 重连：新 Watch 收到建立确认后重新 Bootstrap 全量覆盖，期间变更不丢
        List<WatchEvent> reconnected = new CopyOnWriteArrayList<>();
        WatchClient newWatch = openWatch(reconnected);
        assertThat(bootstrapServices())
                .filteredOn(service -> service.getKey().getNamespace().equals(NAMESPACE))
                .singleElement()
                .satisfies(service -> assertThat(service.getInstancesList())
                        .extracting(Instance::getInstanceId)
                        .containsExactlyInAnyOrder("i1", "i2"));

        registry.deregister(deregisterRequest("i2"));
        TestAwait.await(
                "change delivered on reconnected stream",
                () -> lastType(reconnected, "i2") == ChangeType.CHANGE_TYPE_DELETE);
        newWatch.close();
    }

    // ---- 设施 ----

    /**
     * 建立 watch 流并等待订阅建立确认（契约：首条空批）。确认之后开始 Bootstrap 即为无丢失序。
     */
    private WatchClient openWatch(List<WatchEvent> events) {
        AtomicReference<io.grpc.stub.ClientCallStreamObserver<WatchRequest>> callRef = new AtomicReference<>();
        var observer = new io.grpc.stub.ClientResponseObserver<WatchRequest, WatchEvent>() {
            @Override
            public void beforeStart(io.grpc.stub.ClientCallStreamObserver<WatchRequest> requestStream) {
                callRef.set(requestStream);
            }

            @Override
            public void onNext(WatchEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                // 关闭/断流：用例结束由 WatchClient 主动取消
            }

            @Override
            public void onCompleted() {}
        };
        projectionAsync.watch(
                WatchRequest.newBuilder().setSubscriberId("projection-it").build(), observer);
        TestAwait.await(
                "projection subscription established",
                () -> events.stream().anyMatch(event -> event.getChanges().getServicesCount() == 0));
        return new WatchClient(callRef.get());
    }

    private List<org.mydotey.ai.artemis.proto.projection.v1.InstanceChange> changes(
            List<WatchEvent> events, String instanceId) {
        List<org.mydotey.ai.artemis.proto.projection.v1.InstanceChange> result = new ArrayList<>();
        for (WatchEvent event : events) {
            ChangeBatch batch = event.getChanges();
            batch.getServicesList()
                    .forEach(service -> service.getChangesList().stream()
                            .filter(change ->
                                    change.getInstance().getInstanceId().equals(instanceId))
                            .forEach(result::add));
        }
        return result;
    }

    private ChangeType lastType(List<WatchEvent> events, String instanceId) {
        List<org.mydotey.ai.artemis.proto.projection.v1.InstanceChange> changes = changes(events, instanceId);
        return changes.isEmpty()
                ? ChangeType.CHANGE_TYPE_UNSPECIFIED
                : changes.get(changes.size() - 1).getType();
    }

    private List<BootstrapResponse> bootstrapResponses() {
        Iterator<BootstrapResponse> iterator = projection.bootstrap(
                BootstrapRequest.newBuilder().setRequesterId("projection-it").build());
        List<BootstrapResponse> responses = new ArrayList<>();
        while (iterator.hasNext()) {
            responses.add(iterator.next());
        }
        return responses;
    }

    private List<ServiceInstances> bootstrapServices() {
        List<BootstrapResponse> responses = bootstrapResponses();
        responses.forEach(response -> assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS));
        List<ServiceInstances> services = new ArrayList<>();
        responses.forEach(response -> services.addAll(response.getData().getServicesList()));
        return services;
    }

    private static org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest registerRequest(
            String namespace, String service, String... instanceIds) {
        return org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                .addServices(serviceInstances(namespace, service, instanceIds))
                .build();
    }

    private void registerMany(String namespace, String... serviceNames) {
        org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.Builder request =
                org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder();
        for (String service : serviceNames) {
            request.addServices(serviceInstances(namespace, service, "i1"));
        }
        registry.register(request.build());
    }

    private static ServiceInstances serviceInstances(String namespace, String service, String... instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder()
                .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                        .setNamespace(namespace)
                        .setService(service));
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        return services.build();
    }

    private static HeartbeatRequest heartbeatRequest(String instanceId) {
        return HeartbeatRequest.newBuilder()
                .addServices(serviceInstances(NAMESPACE, SERVICE, instanceId))
                .setSeq(1)
                .build();
    }

    private static DeregisterRequest deregisterRequest(String... instanceIds) {
        DeregisterRequest.Builder request = DeregisterRequest.newBuilder();
        for (String instanceId : instanceIds) {
            request.addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                    .setService(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                            .setNamespace(NAMESPACE)
                            .setService(SERVICE))
                    .setInstanceId(instanceId));
        }
        return request.build();
    }

    private void deregister(String... instanceIds) {
        registry.deregister(deregisterRequest(instanceIds));
    }

    /** watch 客户端句柄：取消流（触发服务端 onCancel → 注销订阅）。 */
    private static final class WatchClient {

        private final io.grpc.stub.ClientCallStreamObserver<WatchRequest> requestObserver;

        private WatchClient(io.grpc.stub.ClientCallStreamObserver<WatchRequest> requestObserver) {
            this.requestObserver = requestObserver;
        }

        private void close() {
            requestObserver.cancel("test done", null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProjectionTestConfig {

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
                            "artemis.registry.bootstrap.shardBytes",
                            String.valueOf(SHARD_BYTES)))
                    .properties();
        }

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            return new InMemorySnapshotStore();
        }
    }
}
