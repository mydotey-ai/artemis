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
import org.mydotey.ai.artemis.discovery.server.upstream.GrpcLogicalInstanceSource;
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
 * 逻辑实例端到端（v0.4 phase-4）：进程内真实 registry + discovery + management 三服务——
 * 逻辑实例经 discovery 查询/订阅可见、与真实实例 union（cluster=logical 并列）、
 * 摘除对逻辑实例同样生效、management 不可用沿用最后已知逻辑实例（fail-open）、
 * management 重启（内存态清零）空快照收敛、未配置不启用（0..1）。
 */
class DiscoveryLogicalInstanceIT {

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
    void should_see_logical_instance_in_query_and_union_with_real_instances() throws Exception {
        startAll();
        register("i1");
        upsertLogical("li-1", "10.9.9.1");

        // union：同 service 两来源并列（真实 cluster-a + 保留 cluster logical）
        awaitUntil(
                "union visible",
                () -> queryClusters().equals(List.of(CLUSTER, GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID)));
        assertThat(instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID)).containsExactly("li-1");
        assertThat(instancesOf(CLUSTER)).containsExactly("i1");

        // 纯逻辑服务（无真实实例）也可见
        upsertLogicalOn("ext-service", "li-2", "10.9.9.2");
        awaitUntil(
                "logical-only service visible",
                () -> instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID, key("ext-service"))
                        .contains("li-2"));
    }

    @Test
    void should_push_logical_changes_to_subscribers() throws Exception {
        startAll();
        register("i1");
        upsertLogical("li-1", "10.9.9.1");
        awaitUntil(
                "logical visible",
                () -> !instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).isEmpty());

        StreamEvents events = subscribe();
        awaitUntil("baseline contains logical", () -> events.countContains("li-1=NEW") > 0);

        // upsert 推送（视图加入 → NEW）
        upsertLogical("li-2", "10.9.9.2");
        awaitUntil("push new", () -> events.anyContains("li-2=NEW"));

        // 删除推送（退出视图 → DELETE）
        deleteLogical("li-1");
        awaitUntil("push delete", () -> events.anyContains("li-1=DELETE"));
    }

    @Test
    void should_filter_ejected_logical_instance() throws Exception {
        startAll();
        upsertLogical("li-1", "10.9.9.1");
        awaitUntil(
                "logical visible",
                () -> !instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).isEmpty());

        // instance 级摘除逻辑实例（同一 ejection 语义，跨 cluster 生效）
        postManagement(
                "/admin/v1/ejections",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"li-1\",\"reason\":\"maintenance\"}");
        awaitUntil(
                "ejected",
                () -> instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).isEmpty());

        postManagement(
                "/admin/v1/ejections/recover",
                "{\"scope\":\"INSTANCE\",\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                        + "\",\"instanceId\":\"li-1\"}");
        awaitUntil(
                "recovered",
                () -> instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).contains("li-1"));
    }

    @Test
    void should_keep_last_known_when_management_down_and_converge_on_empty_snapshot_after_restart() throws Exception {
        startAll();
        upsertLogical("li-1", "10.9.9.1");
        awaitUntil(
                "logical visible",
                () -> !instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).isEmpty());

        // management 不可用：discovery 沿用最后已知逻辑实例（fail-open）
        closeManagement();
        Thread.sleep(1_000); // 越过断流退避首轮，留出「不可用窗口」
        assertThat(instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID)).containsExactly("li-1");

        // management 重启（内存态清零）：discovery 重连收空快照 → 逻辑实例消失
        startManagement();
        awaitUntil(
                "empty snapshot converged",
                () -> instancesOf(GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID).isEmpty());
    }

    @Test
    void should_not_create_logical_cluster_when_management_not_configured() throws Exception {
        startRegistry();
        startDiscovery(false); // 未配置 management：无逻辑源（0..1）
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);

        register("i1");
        awaitUntil("real visible", () -> instancesOf(CLUSTER).contains("i1"));
        assertThat(queryClusters()).containsExactly(CLUSTER);
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
                        "--spring.config.location=optional:classpath:/ejection-it-management.properties",
                        "--spring.application.name=artemis-management",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + managementGrpcPort,
                        "--artemis.management.grpc.port=" + managementGrpcPort, // 条件装配（D-E11）
                        "--artemis.management.registry.endpoint=127.0.0.1:1"); // 惰性，流不触达
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
                                        .addInstances(realInstance(instanceId)))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void upsertLogical(String instanceId, String host) throws Exception {
        upsertLogicalOn(SERVICE, instanceId, host);
    }

    private void upsertLogicalOn(String service, String instanceId, String host) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/logical-instances"))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + service + "\",\"instanceId\":\""
                                + instanceId + "\",\"host\":\"" + host + "\",\"port\":8080,"
                                + "\"metadata\":{\"protocol\":\"http\"}}"))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }

    private void deleteLogical(String instanceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort
                        + "/admin/v1/logical-instances?namespace=" + NAMESPACE + "&service=" + SERVICE
                        + "&instanceId=" + instanceId))
                .header("Authorization", AUTH)
                .DELETE()
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(204);
    }

    private void postManagement(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Object queryAll() {
        return queryStub.query(QueryRequest.newBuilder().build());
    }

    /** 全量查询的 cluster 列表（按返回序；logical 源条目排序不保证，断言用排序副本）。 */
    private List<String> queryClusters() {
        var response = queryStub.query(QueryRequest.newBuilder().build());
        List<String> clusters = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            if (!clusters.contains(service.getCluster())) {
                clusters.add(service.getCluster());
            }
        }
        return clusters.stream().sorted().toList();
    }

    private List<String> instancesOf(String cluster) {
        return instancesOf(cluster, ORDER);
    }

    private List<String> instancesOf(String cluster, org.mydotey.ai.artemis.proto.common.v1.ServiceKey key) {
        var response =
                queryStub.query(QueryRequest.newBuilder().addServices(key).build());
        List<String> ids = new ArrayList<>();
        for (var service : response.getData().getServicesList()) {
            if (cluster.equals(service.getCluster())) {
                for (Instance instance : service.getInstances().getInstancesList()) {
                    ids.add(instance.getInstanceId());
                }
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

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceKey key(String service) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace(NAMESPACE)
                .setService(service)
                .build();
    }

    private static Instance realInstance(String instanceId) {
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
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 订阅流收集器（模式同 DiscoveryEjectionFilterIT）。 */
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
