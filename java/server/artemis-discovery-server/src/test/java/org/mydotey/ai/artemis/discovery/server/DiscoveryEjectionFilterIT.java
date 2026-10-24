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
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 摘除过滤端到端（v0.3 phase-6）：进程内真实 registry + discovery + management 三服务——
 * 摘除后查询与订阅推送被过滤、恢复收敛、management 不可用沿用最后已知规则（fail-open）、
 * management 重启空快照收敛、未配置不过滤（0..1）。
 */
class DiscoveryEjectionFilterIT {

    private static final String CLUSTER = "cluster-a";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey ORDER =
            org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setService(SERVICE)
                    .build();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;

    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registry;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    private int registryExternalPort;
    private int registryPeerPort;
    private int managementGrpcPort;
    private int managementHttpPort;
    private int snapshotGeneration;

    @AfterEach
    void tearDown() {
        closeDiscovery();
        closeManagement();
        if (registryChannel != null) {
            registryChannel.shutdown();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_filter_query_and_push_after_ejection_and_recover() throws Exception {
        startAll();
        register("i1");
        register("i2");
        awaitUntil("both visible", () -> queryInstances().size() == 2);

        StreamEvents events = subscribe();
        awaitUntil("baseline", () -> events.countContains("i1=NEW") > 0 && events.countContains("i2=NEW") > 0);

        eject("i1", "maintenance");
        awaitUntil("query filtered", () -> queryInstances().equals(List.of("i2")));
        awaitUntil("push DELETE", () -> events.anyContains("i1=DELETE"));

        recover("i1");
        awaitUntil("query restored", () -> queryInstances().size() == 2);
        awaitUntil("push NEW", () -> events.anyContains("i1=NEW") && events.anyContains("i1=DELETE"));
    }

    @Test
    void should_keep_filtering_when_management_down_then_converge_after_restart() throws Exception {
        startAll();
        register("i1");
        register("i2");
        awaitUntil("both visible", () -> queryInstances().size() == 2);
        eject("i1", "maintenance");
        awaitUntil("query filtered", () -> queryInstances().equals(List.of("i2")));

        // management 不可用：discovery 沿用最后已知规则（断连不清缓存，D-E3）
        closeManagement();
        Thread.sleep(1_000); // 越过断流退避首轮，留出「不可用窗口」
        assertThat(queryInstances()).isEqualTo(List.of("i2"));

        // management 重启（内存态清零）：discovery 重连收空快照 → i1 恢复可见
        startManagement();
        awaitUntil("empty snapshot converged", () -> queryInstances().size() == 2);
    }

    @Test
    void should_not_filter_when_management_not_configured() throws Exception {
        startRegistry();
        startDiscovery(false); // 未配置 management：空链直通（D-E4）
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);

        register("i1");
        awaitUntil("visible", () -> queryInstances().equals(List.of("i1")));
    }

    // ---- 服务起停 ----

    private void startAll() throws IOException {
        startRegistry();
        startManagement();
        startDiscovery(true);
    }

    private void startRegistry() throws IOException {
        registryExternalPort = freePort();
        registryPeerPort = freePort();
        snapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/discovery-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + registryExternalPort,
                        "--artemis.registry.internal.port=" + registryPeerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.snapshot.path="
                                + tempDir.resolve("registry-snapshot-" + snapshotGeneration + ".bin"),
                        "--artemis.registry.lease.ttlMs=600000",
                        "--artemis.registry.shutdown.drainMs=0");
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", registryExternalPort)
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), AUTH);
        registry = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void startManagement() {
        if (managementGrpcPort == 0) {
            managementGrpcPort = freePortUnchecked(); // 固定预分配：重启复用同端口
        }
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        // 隔离 discovery 测试 classpath 的 application.properties（健康组等键冲突）；
                        // 所需键全部显式传参（gRPC 端口占位符映射不可依赖，直接钉 spring.grpc.server.port）
                        "--spring.config.location=optional:classpath:/ejection-it-management.properties",
                        "--spring.application.name=artemis-management",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + managementGrpcPort,
                        "--artemis.management.grpc.port=" + managementGrpcPort, // 条件装配（D-E11）
                        "--artemis.management.registry.endpoint=127.0.0.1:1"); // 惰性，规则流不触达
        managementHttpPort = managementContext.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private void startDiscovery(boolean withManagement) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.application.name=artemis-discovery",
                "--spring.main.web-application-type=none",
                "--management.server.port=0",
                "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--spring.grpc.server.port=0",
                "--spring.grpc.server.shutdown.grace-period=1s",
                "--artemis.discovery.registry.clusters={\"" + CLUSTER + "\":\"127.0.0.1:" + registryPeerPort + "\"}",
                "--artemis.discovery.management.reconnectBackoffMinMs=200", // 测试收敛加速
                "--artemis.discovery.management.reconnectBackoffMaxMs=1000"));
        if (withManagement) {
            args.add("--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort);
        }
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class).run(args.toArray(String[]::new));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
    }

    private void closeDiscovery() {
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
        if (discoveryContext != null) {
            discoveryContext.close();
            discoveryContext = null;
        }
    }

    private void closeManagement() {
        if (managementContext != null) {
            managementContext.close();
            managementContext = null;
        }
    }

    private int discoveryPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    // ---- 驱动 ----

    private void register(String instanceId) {
        assertThat(registry.register(RegisterRequest.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                        .setKey(ORDER)
                                        .addInstances(instance(instanceId)))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void eject(String instanceId, String reason) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/services/" + NAMESPACE + "/"
                        + SERVICE + "/instances/" + instanceId + "/eject"))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"" + reason + "\"}"))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void recover(String instanceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/services/" + NAMESPACE + "/"
                        + SERVICE + "/instances/" + instanceId + "/recover"))
                .header("Authorization", AUTH)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Object queryAll() {
        return queryStub.query(QueryRequest.newBuilder().build());
    }

    private List<String> queryInstances() {
        var response =
                queryStub.query(QueryRequest.newBuilder().addServices(ORDER).build());
        List<String> ids = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            for (Instance instance : service.getInstances().getInstancesList()) {
                ids.add(instance.getInstanceId());
            }
        }
        return ids;
    }

    private StreamEvents subscribe() {
        StreamEvents events = new StreamEvents();
        DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel)
                .subscribe(SubscribeRequest.newBuilder().build(), events);
        return events;
    }

    private static Instance instance(String instanceId) {
        return Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(8080)
                .build();
    }

    private static Status.Code statusOf(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return null;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }

    private static int freePortUnchecked() {
        try {
            return freePort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 订阅流收集器（模式同 DiscoveryMultiClusterIT）。 */
    private static final class StreamEvents implements StreamObserver<SubscriptionEvent> {

        final List<SubscriptionEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(SubscriptionEvent event) {
            received.add(event);
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onCompleted() {}

        boolean anyContains(String fragment) {
            return descriptions().stream().anyMatch(description -> description.contains(fragment));
        }

        long countContains(String fragment) {
            return descriptions().stream()
                    .filter(description -> description.contains(fragment))
                    .count();
        }

        private List<String> descriptions() {
            List<String> out = new ArrayList<>();
            for (SubscriptionEvent event : received) {
                if (!event.hasService()) {
                    out.add("established");
                    continue;
                }
                out.add(event.getService().getKey().getService() + ":" + changes(event));
            }
            return out;
        }

        private static String changes(SubscriptionEvent event) {
            List<String> parts = new ArrayList<>();
            for (var change : event.getService().getChangesList()) {
                parts.add(change.getInstance().getInstanceId() + "="
                        + change.getType().name().replace("CHANGE_TYPE_", ""));
            }
            return parts.toString();
        }
    }
}
