package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
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
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
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
 * 分组路由端到端（v0.5 phase-3）：进程内真实 registry + discovery + management 三服务——
 * route-rule 经规则流下发后 Group filter 生效：查询/推送/对账三出口一致、release 跨 0
 * 权重触发视图变化、回滚还原、兜底目标承接、逻辑实例同判定。
 */
class DiscoveryGroupRoutingIT {

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
    void should_filter_view_and_release_restores_across_three_exits() throws Exception {
        startAll();
        register("i1", Map.of("group", "beta"));
        register("i2", Map.of("group", "stable"));
        awaitUntil("both visible", () -> queryInstances().size() == 2);

        StreamEvents events = subscribe();
        awaitUntil("baseline", () -> events.countContains("i1=NEW") > 0 && events.countContains("i2=NEW") > 0);

        // 创建规则（beta 组编辑列 30 + 兜底编辑列 70）：生效列全 0——未 release = 全不承接，
        // 三出口一致（查询空 + 订阅收 DELETE 直投 + checksums 滤空缺项）
        createRule(
                "[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":30},{\"selector\":{},\"unreleasedWeight\":70}]");
        awaitUntil("query empty before release", () -> queryInstances().isEmpty());
        awaitUntil(
                "push DELETEs", () -> events.countContains("i1=DELETE") > 0 && events.countContains("i2=DELETE") > 0);
        assertThat(checksumServiceCount()).isZero();

        // release：生效列跨 0 → 视图回归（NEW 直投），三出口恢复一致
        release();
        awaitUntil("query restored", () -> queryInstances().size() == 2);
        awaitUntil("push NEWs", () -> events.countContains("i1=NEW") > 1);
        assertThat(checksumServiceCount()).isEqualTo(1);
    }

    @Test
    void should_drop_unmatched_instances_when_no_serving_target() throws Exception {
        startAll();
        register("i1", Map.of("group", "beta"));
        register("i2", Map.of("group", "stable"));
        awaitUntil("both visible", () -> queryInstances().size() == 2);

        // 仅 beta 目标（100）、无兜底：stable 实例未命中任何 selector 且无承接 → 摘出
        createRule("[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":100}]");
        release();
        awaitUntil("unmatched dropped", () -> queryInstances().equals(List.of("i1")));
    }

    @Test
    void should_restore_view_on_rollback() throws Exception {
        startAll();
        register("i1", Map.of("group", "beta"));
        register("i2", Map.of("group", "stable"));
        awaitUntil("both visible", () -> queryInstances().size() == 2);

        // release#1：全承接 → release#2：兜底 0（i2 摘出）→ rollback 到 #1：i2 回归
        createRule(
                "[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":100},{\"selector\":{},\"unreleasedWeight\":100}]");
        release();
        awaitUntil("all serving", () -> queryInstances().size() == 2);
        long firstReleaseId = latestReleaseId();

        updateRule(
                "[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":100},{\"selector\":{},\"unreleasedWeight\":0}]");
        release();
        awaitUntil("fallback dropped", () -> queryInstances().equals(List.of("i1")));

        rollback(firstReleaseId);
        awaitUntil("rollback restored", () -> queryInstances().size() == 2);
    }

    @Test
    void should_apply_rule_to_logical_instances() throws Exception {
        startAll();
        register("i-real", Map.of("group", "beta"));
        upsertLogical("i-logical", Map.of("group", "beta"));
        awaitUntil("both visible", () -> queryInstances().containsAll(List.of("i-real", "i-logical")));

        // beta 组 weight 0：真实与逻辑实例同判定（logical cluster 投影同过滤）
        createRule(
                "[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":0},{\"selector\":{},\"unreleasedWeight\":70}]");
        awaitUntil("both dropped", () -> queryInstances().isEmpty());
    }

    // ---- 服务起停（形态同 DiscoveryEjectionFilterIT） ----

    private void startAll() throws IOException {
        startRegistry();
        startManagement();
        startDiscovery();
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
                        "--artemis.management.registry.endpoint=127.0.0.1:1"); // 惰性，规则流不触达
        managementHttpPort = managementContext.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private void startDiscovery() {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--spring.main.web-application-type=none",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=0",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--artemis.discovery.registry.clusters={\"" + CLUSTER + "\":\"127.0.0.1:" + registryPeerPort
                                + "\"}",
                        "--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort,
                        "--artemis.discovery.management.reconnectBackoffMinMs=200", // 测试收敛加速
                        "--artemis.discovery.management.reconnectBackoffMaxMs=1000");
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

    private void register(String instanceId, Map<String, String> metadata) {
        assertThat(registry.register(RegisterRequest.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                        .setKey(ORDER)
                                        .addInstances(instance(instanceId, metadata)))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void upsertLogical(String instanceId, Map<String, String> metadata) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/logical-instances"))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE + "\",\"instanceId\":\""
                                + instanceId + "\",\"host\":\"10.0.0.9\",\"port\":8080,\"metadata\":"
                                + metadataJson(metadata)
                                + "}"))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }

    private void createRule(String targetsJson) throws Exception {
        assertThat(postManagement(
                                "/admin/v1/route-rules",
                                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                                        + "\",\"name\":\"release\",\"targets\":" + targetsJson + "}")
                        .statusCode())
                .isEqualTo(201);
    }

    private void updateRule(String targetsJson) throws Exception {
        assertThat(putManagement(
                                "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/release",
                                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE
                                        + "\",\"name\":\"release\",\"targets\":"
                                        + targetsJson + "}")
                        .statusCode())
                .isEqualTo(200);
    }

    /** 规则操作路径：/route-rules/{ns}/{svc}/{name} + 动作后缀（规则名固定 "release"）。 */
    private static String rulePath(String action) {
        return "/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/release" + action;
    }

    private void release() throws Exception {
        assertThat(postManagement(rulePath("/release"), "").statusCode()).isEqualTo(200);
    }

    private void rollback(long releaseId) throws Exception {
        assertThat(postManagement(rulePath("/rollback"), "{\"releaseId\":" + releaseId + "}")
                        .statusCode())
                .isEqualTo(200);
    }

    /** 最新 release 记录 id（列表 id 降序，首条即最新）。 */
    private long latestReleaseId() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + managementHttpPort + "/admin/v1/route-rules/" + NAMESPACE
                                + "/" + SERVICE + "/release/releases?limit=1"))
                        .header("Authorization", AUTH)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonParser.parseString(response.body())
                .getAsJsonObject()
                .getAsJsonArray("releases")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsLong();
    }

    private HttpResponse<String> postManagement(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putManagement(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementHttpPort + path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
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

    /** 对账出口：ORDER 过滤后非空才出现在 checksum 表（滤空缺项 = 视图无实例）。 */
    private int checksumServiceCount() {
        var response = DiscoveryReconciliationServiceGrpc.newBlockingStub(discoveryChannel)
                .checksum(ChecksumRequest.newBuilder().addServices(ORDER).build());
        int count = 0;
        for (var cluster : response.getData().getClustersList()) {
            count += cluster.getServicesCount();
        }
        return count;
    }

    private StreamEvents subscribe() {
        StreamEvents events = new StreamEvents();
        DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel)
                .subscribe(SubscribeRequest.newBuilder().build(), events);
        return events;
    }

    private static String metadataJson(Map<String, String> metadata) {
        StringBuilder json = new StringBuilder("{");
        List<String> entries = new ArrayList<>();
        metadata.forEach((key, value) -> entries.add("\"" + key + "\":\"" + value + "\""));
        json.append(String.join(",", entries)).append("}");
        return json.toString();
    }

    private static Instance instance(String instanceId, Map<String, String> metadata) {
        Instance.Builder builder = Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(8080);
        metadata.forEach(builder::putMetadata);
        return builder.build();
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
