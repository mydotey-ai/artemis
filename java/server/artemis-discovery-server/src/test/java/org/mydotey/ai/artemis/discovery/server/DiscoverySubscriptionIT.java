package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 订阅推送端到端（v0.3 phase-3，ADR-019）：进程内真实 registry + discovery，discovery.v1
 * 订阅 stub 直连驱动——确认 + 基线、增量亚秒可见、labels 视图语义、selector 隔离、
 * 上游覆盖 diff 续推（流不断）、未就绪拒绝、取消清理。
 */
class DiscoverySubscriptionIT {

    private static final ServiceKey ORDER = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("order-service")
            .build();
    private static final ServiceKey PAY = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("pay-service")
            .build();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private int registrySnapshotGeneration;
    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;

    @AfterEach
    void tearDown() {
        closeChannels();
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_stream_confirmation_and_baseline_on_subscribe() throws Exception {
        startRegistryAndDiscovery();
        register(ORDER, instance("i1", 8080));
        register(ORDER, instance("i2", 8080));
        register(PAY, instance("p1", 8080));
        awaitProjected(3);

        StreamEvents events = subscribe(SubscribeRequest.newBuilder()
                .addServices(ORDER)
                .addServices(PAY)
                .build());

        awaitUntil("confirmation", () -> events.received.size() >= 1);
        assertThat(events.received.get(0).hasService()).isFalse(); // 首条空确认
        awaitUntil("baseline", () -> events.received.size() >= 3);
        // 基线条目顺序随 selector 集合迭代序（无跨 service 顺序语义），批内保持产生序
        assertThat(events.anyContains("order-service:[i1=NEW, i2=NEW]")).isTrue();
        assertThat(events.anyContains("pay-service:[p1=NEW]")).isTrue();
        assertThat(events.received.get(1).getCluster()).isEqualTo("test-cluster");
        assertThat(events.errors).isEmpty();
    }

    @Test
    void should_push_increment_sub_second_on_stream() throws Exception {
        startRegistryAndDiscovery();
        StreamEvents events = subscribe(SubscribeRequest.newBuilder().build()); // 空 = 全部服务
        awaitUntil("confirmation", () -> !events.received.isEmpty());

        long startAt = System.nanoTime();
        register(ORDER, instance("i1", 8080));
        awaitUntil("increment NEW", () -> events.anyContains("i1=NEW"));
        long elapsedMs = (System.nanoTime() - startAt) / 1_000_000;
        assertThat(elapsedMs).as("注册→订阅流可见应亚秒").isLessThan(1_000);

        update(ORDER, instance("i1", 9090));
        awaitUntil("increment UPDATE", () -> events.anyContains("i1=UPDATE"));

        deregister(ORDER, "i1");
        awaitUntil("increment DELETE", () -> events.anyContains("i1=DELETE"));
        assertThat(events.errors).isEmpty();
    }

    @Test
    void should_isolate_streams_by_selector() throws Exception {
        startRegistryAndDiscovery();
        awaitDiscoveryReady();

        StreamEvents byService =
                subscribe(SubscribeRequest.newBuilder().addServices(ORDER).build());
        StreamEvents byLabels =
                subscribe(SubscribeRequest.newBuilder().putLabels("group", "a").build());

        register(ORDER, instance("i1", 8080, "group", "b")); // 命中 byService，不命中 byLabels
        register(PAY, instance("p1", 8080, "group", "a")); // 命中 byLabels，不命中 byService

        awaitUntil("byService increment", () -> byService.anyContains("order-service"));
        awaitUntil("byLabels increment", () -> byLabels.anyContains("pay-service"));
        assertThat(byService.descriptions()).noneMatch(d -> d.contains("pay-service"));
        assertThat(byLabels.descriptions()).noneMatch(d -> d.contains("order-service"));
    }

    @Test
    void should_push_labels_view_semantics_on_stream() throws Exception {
        startRegistryAndDiscovery();
        StreamEvents events =
                subscribe(SubscribeRequest.newBuilder().putLabels("group", "a").build());
        awaitUntil("confirmation", () -> !events.received.isEmpty());

        register(ORDER, instance("i1", 8080, "group", "a"));
        awaitUntil("view NEW", () -> events.anyContains("i1=NEW"));

        register(ORDER, instance("i2", 8080, "group", "b")); // 不入视图
        register(ORDER, instance("i3", 8080, "group", "a"));
        awaitUntil("i3 NEW", () -> events.anyContains("i3=NEW"));
        assertThat(events.descriptions()).noneMatch(d -> d.contains("i2")); // 不匹配实例零推送

        update(ORDER, instance("i2", 8080, "group", "a")); // 退出→进入：视图加入 = NEW
        awaitUntil("i2 joins view", () -> events.anyContains("i2=NEW"));

        update(ORDER, instance("i1", 8080, "group", "b")); // 进入→退出：视图移除 = DELETE
        awaitUntil("i1 leaves view", () -> events.anyContains("i1=DELETE"));
    }

    @Test
    void should_stay_connected_and_converge_via_diff_when_upstream_replaced() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);
        awaitDiscoveryReady();

        StreamEvents events = subscribe(SubscribeRequest.newBuilder().build());
        register(ORDER, instance("i1", 8080));
        awaitUntil("increment", () -> events.anyContains("i1=NEW"));

        // registry 重启（独立快照目录 = 空表）：discovery 重连覆盖，diff 续推 DELETE——流不断
        closeRegistryChannel();
        registryContext.close();
        registryContext = null;
        startRegistry(registryPort, peerPort);

        awaitUntil("diff DELETE", () -> events.anyContains("i1=DELETE"));
        assertThat(events.errors).as("上游重连不得断订阅流").isEmpty();

        register(ORDER, instance("i2", 8080)); // 新 registry 的增量继续走原流
        awaitUntil("post-resync increment", () -> events.anyContains("i2=NEW"));
    }

    @Test
    void should_reject_subscribe_before_first_sync() throws Exception {
        startDiscovery(freePort()); // 上游无人监听
        StreamEvents events = subscribe(SubscribeRequest.newBuilder().build());
        awaitUntil("rejected", () -> !events.errors.isEmpty());
        assertThat(events.errors.get(0).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void should_clean_up_subscription_after_client_cancel() throws Exception {
        startRegistryAndDiscovery();
        org.mydotey.ai.artemis.discovery.domain.SubscriptionManager manager =
                discoveryContext.getBean(org.mydotey.ai.artemis.discovery.domain.SubscriptionManager.class);
        StreamEvents events = subscribe(SubscribeRequest.newBuilder().build());
        awaitUntil("confirmation", () -> !events.received.isEmpty());
        awaitUntil("registered in manager", () -> manager.size() == 1);

        discoveryChannel.shutdownNow(); // 客户端断开 → 服务端 cancel 清理
        awaitUntil("subscription cleaned up", () -> manager.size() == 0);
        register(ORDER, instance("i1", 8080));

        // 服务端存活：新通道新订阅正常服务（取消路径不残留、不崩）
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        StreamEvents fresh = subscribe(SubscribeRequest.newBuilder().build());
        awaitUntil("fresh subscription works", () -> fresh.anyContains("i1=NEW"));
    }

    // ---- 起停与驱动（模式同 DiscoveryProjectionIT）----

    private void startRegistryAndDiscovery() throws Exception {
        startRegistry(freePort(), freePort());
        startDiscovery(registryPeerPort);
        awaitDiscoveryReady();
    }

    /** 投影可见等待（基线前的 register 需先到达投影，防基线竞态）。 */
    private void awaitProjected(int expectedInstances) {
        DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub stub =
                DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("projected " + expectedInstances, () -> {
            var response = stub.query(QueryRequest.newBuilder().build());
            int count = 0;
            for (var service : response.getData().getServicesList()) {
                count += service.getInstances().getInstancesCount();
            }
            return count >= expectedInstances;
        });
    }

    private int registryPeerPort;

    private void startRegistry(int externalPort, int peerPort) {
        registryPeerPort = peerPort;
        registrySnapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(registryArgs(externalPort, peerPort).toArray(String[]::new));
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", externalPort)
                .usePlaintext()
                .build();
        registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth()));
    }

    private void startDiscovery(int peerPort) {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(discoveryArgs(peerPort).toArray(String[]::new));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
    }

    private List<String> registryArgs(int externalPort, int peerPort) {
        return List.of(
                "--spring.config.location=optional:classpath:/discovery-it-registry.properties",
                "--spring.application.name=artemis-registry",
                "--spring.main.web-application-type=none",
                "--spring.grpc.server.shutdown.grace-period=1s",
                "--management.server.port=0",
                "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--spring.grpc.server.port=" + externalPort,
                "--artemis.registry.internal.port=" + peerPort,
                "--artemis.registry.internal.address=127.0.0.1",
                "--artemis.registry.snapshot.path="
                        + tempDir.resolve("registry-snapshot-" + registrySnapshotGeneration + ".bin"),
                "--artemis.registry.lease.ttlMs=600000",
                "--artemis.registry.shutdown.drainMs=0");
    }

    private List<String> discoveryArgs(int peerPort) {
        return List.of(
                "--spring.application.name=artemis-discovery",
                "--spring.main.web-application-type=none",
                "--management.server.port=0",
                "--spring.grpc.server.port=0",
                "--artemis.discovery.upstream.endpoints=127.0.0.1:" + peerPort,
                "--artemis.discovery.upstream.clusterId=test-cluster",
                "--artemis.discovery.upstream.reconnectBackoffMinMs=100",
                "--artemis.discovery.upstream.reconnectBackoffMaxMs=200");
    }

    private int discoveryPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    private static Metadata basicAuth() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue("artemis", "artemis"));
        return headers;
    }

    private StreamEvents subscribe(SubscribeRequest request) {
        StreamEvents events = new StreamEvents();
        DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel).subscribe(request, events);
        return events;
    }

    private void register(ServiceKey key, Instance instance) {
        assertThat(registryStub
                        .register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                                .addServices(serviceInstances(key, instance))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void update(ServiceKey key, Instance instance) {
        assertThat(registryStub
                        .update(org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest.newBuilder()
                                .addServices(serviceInstances(key, instance))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void deregister(ServiceKey key, String instanceId) {
        assertThat(registryStub
                        .deregister(org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest.newBuilder()
                                .addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                                        .setService(key)
                                        .setInstanceId(instanceId))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceInstances serviceInstances(
            ServiceKey key, Instance instance) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                .setKey(key)
                .addInstances(instance)
                .build();
    }

    private static Instance instance(String instanceId, int port) {
        return instance(instanceId, port, null, null);
    }

    private static Instance instance(String instanceId, int port, String metadataKey, String metadataValue) {
        Instance.Builder builder = Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(port);
        if (metadataKey != null) {
            builder.putMetadata(metadataKey, metadataValue);
        }
        return builder.build();
    }

    /** discovery ready = 查询非 UNAVAILABLE（复用查询端口探测 readiness，同 phase-2 IT）。 */
    private void awaitDiscoveryReady() {
        DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub stub =
                DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("discovery ready", () -> {
            try {
                stub.query(QueryRequest.newBuilder().build());
                return true;
            } catch (StatusRuntimeException e) {
                return e.getStatus().getCode() != Status.Code.UNAVAILABLE;
            }
        });
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
        closeRegistryChannel();
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
    }

    private void closeRegistryChannel() {
        if (registryChannel != null) {
            registryChannel.shutdown();
            registryChannel = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }

    /** 订阅流收集器：事件与错误并发安全记录，可读描述便于断言。 */
    private static final class StreamEvents implements StreamObserver<SubscriptionEvent> {

        final List<SubscriptionEvent> received = new CopyOnWriteArrayList<>();
        final List<Status> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(SubscriptionEvent event) {
            received.add(event);
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(Status.fromThrowable(throwable));
        }

        @Override
        public void onCompleted() {
            // 服务端不主动完成订阅流（断开走 onError）
        }

        List<String> descriptions() {
            List<String> out = new ArrayList<>();
            for (SubscriptionEvent event : received) {
                out.add(describe(event));
            }
            return out;
        }

        String described(int index) {
            return describe(received.get(index));
        }

        boolean anyContains(String fragment) {
            return descriptions().stream().anyMatch(description -> description.contains(fragment));
        }

        private static String describe(SubscriptionEvent event) {
            if (!event.hasService()) {
                return "established";
            }
            StringBuilder out = new StringBuilder(event.getService().getKey().getService()).append(":");
            List<String> parts = new ArrayList<>();
            for (var change : event.getService().getChangesList()) {
                parts.add(change.getInstance().getInstanceId() + "="
                        + change.getType().name().replace("CHANGE_TYPE_", ""));
            }
            return out.append(parts).toString();
        }
    }
}
