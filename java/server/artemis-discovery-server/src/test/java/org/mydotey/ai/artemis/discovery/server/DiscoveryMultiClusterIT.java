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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 多集群聚合端到端（v0.3 phase-5，ADR-002/014）：进程内 2 个真实 registry 集群 +
 * 1 个 discovery（JSON map 配两上游）——union 查询、cluster 过滤、订阅归因与过滤、
 * 对账过滤、新鲜度独立性（跨源隔离）、readiness 全量门。
 */
class DiscoveryMultiClusterIT {

    private static final String CLUSTER_A = "cluster-a";
    private static final String CLUSTER_B = "cluster-b";

    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey ORDER =
            org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                    .setNamespace("default")
                    .setService("order-service")
                    .build();
    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey PAY =
            org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                    .setNamespace("default")
                    .setService("pay-service")
                    .build();

    @TempDir
    Path tempDir;

    private final Map<String, ConfigurableApplicationContext> registryContexts = new HashMap<>();
    private final Map<String, RegistryServiceGrpc.RegistryServiceBlockingStub> registryStubs = new HashMap<>();
    private final Map<String, ManagedChannel> registryChannels = new HashMap<>();
    private final Map<String, Integer> snapshotGenerations = new HashMap<>();

    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel discoveryChannel;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    @AfterEach
    void tearDown() {
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
        }
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        registryChannels.values().forEach(ManagedChannel::shutdown);
        registryContexts.values().forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void should_union_query_across_clusters() throws Exception {
        startCluster(CLUSTER_A);
        startCluster(CLUSTER_B);
        startDiscovery();
        awaitDiscoveryReady();

        register(CLUSTER_A, ORDER, instance("i-a", 8080));
        register(CLUSTER_B, ORDER, instance("i-b", 8080)); // 同 ServiceKey 双 cluster 并列
        register(CLUSTER_B, PAY, instance("p-b", 8080));

        var response = query(
                QueryRequest.newBuilder().addServices(ORDER).addServices(PAY).build());
        assertThat(response.getData().getServicesList()).hasSize(3);
        Map<String, List<String>> byCluster = instancesByCluster(response);
        assertThat(byCluster).containsOnlyKeys(CLUSTER_A, CLUSTER_B);
        assertThat(byCluster.get(CLUSTER_A)).containsExactly("i-a");
        // cluster 内服务迭代序是 CHM 实现细节（FIX-3 纪律）：无序断言
        assertThat(byCluster.get(CLUSTER_B)).containsExactlyInAnyOrder("i-b", "p-b");
    }

    @Test
    void should_filter_query_by_cluster_selector() throws Exception {
        startCluster(CLUSTER_A);
        startCluster(CLUSTER_B);
        startDiscovery();
        awaitDiscoveryReady();

        register(CLUSTER_A, ORDER, instance("i-a", 8080));
        register(CLUSTER_B, ORDER, instance("i-b", 8080));

        var filtered = query(QueryRequest.newBuilder()
                .addServices(ORDER)
                .addClusters(CLUSTER_A)
                .build());
        assertThat(filtered.getData().getServicesList()).hasSize(1);
        assertThat(filtered.getData().getServices(0).getCluster()).isEqualTo(CLUSTER_A);
        assertThat(filtered.getData()
                        .getServices(0)
                        .getInstances()
                        .getInstances(0)
                        .getInstanceId())
                .isEqualTo("i-a");

        // 未知 cluster 名：空结果成功（非错误）
        var unknown = query(QueryRequest.newBuilder()
                .addServices(ORDER)
                .addClusters("cluster-x")
                .build());
        assertThat(unknown.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(unknown.getData().getServicesCount()).isZero();
    }

    @Test
    void should_push_only_matching_clusters_to_subscription() throws Exception {
        startCluster(CLUSTER_A);
        startCluster(CLUSTER_B);
        startDiscovery();
        awaitDiscoveryReady();

        StreamEvents filtered =
                subscribe(SubscribeRequest.newBuilder().addClusters(CLUSTER_A).build());
        StreamEvents all = subscribe(SubscribeRequest.newBuilder().build());
        awaitUntil("confirmations", () -> !filtered.received.isEmpty() && !all.received.isEmpty());

        register(CLUSTER_B, ORDER, instance("i-b", 8080));
        register(CLUSTER_A, ORDER, instance("i-a", 8080));

        awaitUntil("cluster-a push", () -> filtered.anyContains("i-a"));
        assertThat(filtered.descriptions()).noneMatch(d -> d.contains("i-b")); // 非命中 cluster 不推
        assertThat(filtered.received.stream().filter(SubscriptionEvent::hasService))
                .allSatisfy(event -> assertThat(event.getCluster()).isEqualTo(CLUSTER_A));

        awaitUntil("cluster-b push", () -> all.anyContains("i-b"));
        assertThat(all.received.stream()
                        .filter(event ->
                                event.hasService() && event.getCluster().equals(CLUSTER_B))
                        .map(event ->
                                event.getService().getChanges(0).getInstance().getInstanceId()))
                .containsExactly("i-b"); // 无过滤订阅收到 B 的增量，cluster 归因正确
        assertThat(filtered.errors).isEmpty();
        assertThat(all.errors).isEmpty();
    }

    @Test
    void should_filter_checksums_and_upstreams_by_cluster() throws Exception {
        startCluster(CLUSTER_A);
        startCluster(CLUSTER_B);
        startDiscovery();
        awaitDiscoveryReady();

        register(CLUSTER_A, ORDER, instance("i-a", 8080));
        register(CLUSTER_B, PAY, instance("p-b", 8080));
        awaitUntil("projected", () -> queryAllInstances() >= 2);

        var response = DiscoveryReconciliationServiceGrpc.newBlockingStub(discoveryChannel)
                .checksum(ChecksumRequest.newBuilder()
                        .addServices(ORDER)
                        .addServices(PAY)
                        .addClusters(CLUSTER_A)
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getClustersList()).hasSize(1);
        assertThat(response.getData().getClusters(0).getCluster()).isEqualTo(CLUSTER_A);
        assertThat(response.getData().getClusters(0).getServices(0).getKey().getService())
                .isEqualTo("order-service");
        // 新鲜度只携带命中 cluster
        assertThat(response.getData().getUpstreamsList()).hasSize(1);
        assertThat(response.getData().getUpstreams(0).getCluster()).isEqualTo(CLUSTER_A);
    }

    @Test
    void should_keep_cluster_freshness_independent() throws Exception {
        startCluster(CLUSTER_A);
        startCluster(CLUSTER_B);
        startDiscovery();
        awaitDiscoveryReady();

        register(CLUSTER_A, ORDER, instance("i-a", 8080));
        register(CLUSTER_B, ORDER, instance("i-b", 8080));
        awaitUntil("projected", () -> queryAllInstances() >= 2);

        // 停 A：仅 A 失去新鲜度（DISCONNECTED/SYNCING 随重连轮振荡），B 保持 SYNCED、A 投影冻结可查
        stopCluster(CLUSTER_A);
        awaitUntil(
                "cluster-a degraded", () -> upstreamState(CLUSTER_A) != UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED);
        awaitUntil("cluster-b synced", () -> upstreamState(CLUSTER_B) == UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED);
        var frozen = query(QueryRequest.newBuilder()
                .addServices(ORDER)
                .addClusters(CLUSTER_A)
                .build());
        assertThat(frozen.getData()
                        .getServices(0)
                        .getInstances()
                        .getInstances(0)
                        .getInstanceId())
                .isEqualTo("i-a");

        // 重启 A（空表）：恢复 SYNCED、陈旧数据被覆盖
        startCluster(CLUSTER_A);
        awaitUntil(
                "cluster-a recovered", () -> upstreamState(CLUSTER_A) == UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED);
        awaitUntil("stale covered", () -> {
            var response = query(QueryRequest.newBuilder()
                    .addServices(ORDER)
                    .addClusters(CLUSTER_A)
                    .build());
            return response.getData().getServicesCount() == 0;
        });
        register(CLUSTER_A, ORDER, instance("i-a2", 8080));
        awaitUntil("re-synced", () -> {
            var response = query(QueryRequest.newBuilder()
                    .addServices(ORDER)
                    .addClusters(CLUSTER_A)
                    .build());
            return response.getData().getServicesCount() == 1
                    && response.getData()
                            .getServices(0)
                            .getInstances()
                            .getInstances(0)
                            .getInstanceId()
                            .equals("i-a2");
        });
    }

    @Test
    void should_gate_readiness_on_all_upstreams() throws Exception {
        startCluster(CLUSTER_B); // 只起 B：A 缺席 → union 不完整 → 不 ready
        startDiscovery();
        awaitUntil("unavailable before all synced", () -> statusOf(() -> queryAll()) == Status.Code.UNAVAILABLE);

        startCluster(CLUSTER_A);
        awaitDiscoveryReady();

        register(CLUSTER_A, ORDER, instance("i-a", 8080));
        register(CLUSTER_B, ORDER, instance("i-b", 8080));
        awaitUntil("both visible", () -> queryAllInstances() >= 2);
    }

    // ---- 起停与驱动 ----

    /** cluster → peer 端口分配表：discovery 先起时预分配，registry 后起时复用同端口。 */
    private final Map<String, Integer> clusterPeerPorts = new HashMap<>();

    private void startCluster(String cluster) {
        startCluster(
                cluster,
                freePortUnchecked(),
                clusterPeerPorts.computeIfAbsent(cluster, ignored -> freePortUnchecked()));
    }

    private void startCluster(String cluster, int externalPort, int peerPort) {
        clusterPeerPorts.put(cluster, peerPort);
        int generation = snapshotGenerations.merge(cluster, 1, Integer::sum);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(registryArgs(externalPort, peerPort, cluster, generation).toArray(String[]::new));
        registryContexts.put(cluster, context);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", externalPort)
                .usePlaintext()
                .build();
        registryChannels.put(cluster, channel);
        registryStubs.put(
                cluster,
                RegistryServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth())));
    }

    private void stopCluster(String cluster) {
        ManagedChannel channel = registryChannels.remove(cluster);
        if (channel != null) {
            channel.shutdown();
        }
        registryStubs.remove(cluster);
        ConfigurableApplicationContext context = registryContexts.remove(cluster);
        if (context != null) {
            context.close();
        }
    }

    private void startDiscovery() {
        int peerA = clusterPeerPorts.computeIfAbsent(CLUSTER_A, ignored -> freePortUnchecked());
        int peerB = clusterPeerPorts.computeIfAbsent(CLUSTER_B, ignored -> freePortUnchecked());
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(discoveryArgs(peerA, peerB).toArray(String[]::new));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
    }

    private List<String> registryArgs(int externalPort, int peerPort, String cluster, int generation) {
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
                        + tempDir.resolve("registry-" + cluster + "-snapshot-" + generation + ".bin"),
                "--artemis.registry.lease.ttlMs=600000",
                "--artemis.registry.shutdown.drainMs=0");
    }

    private List<String> discoveryArgs(int peerA, int peerB) {
        return List.of(
                "--spring.application.name=artemis-discovery",
                "--spring.main.web-application-type=none",
                "--management.server.port=0",
                "--spring.grpc.server.port=0",
                "--artemis.discovery.registry.clusters={\"cluster-a\":\"127.0.0.1:" + peerA
                        + "\",\"cluster-b\":\"127.0.0.1:" + peerB + "\"}",
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

    private void register(String cluster, org.mydotey.ai.artemis.proto.common.v1.ServiceKey key, Instance instance) {
        assertThat(registryStubs
                        .get(cluster)
                        .register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                        .setKey(key)
                                        .addInstances(instance))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private static Instance instance(String instanceId, int port) {
        return Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(port)
                .build();
    }

    private org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse query(QueryRequest request) {
        return queryStub.query(request);
    }

    private org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse queryAll() {
        return query(QueryRequest.newBuilder().build());
    }

    private UpstreamSyncState upstreamState(String cluster) {
        for (var upstream : queryAll().getData().getUpstreamsList()) {
            if (upstream.getCluster().equals(cluster)) {
                return upstream.getState();
            }
        }
        return null;
    }

    private int queryAllInstances() {
        int count = 0;
        for (var service : queryAll().getData().getServicesList()) {
            count += service.getInstances().getInstancesCount();
        }
        return count;
    }

    private static Map<String, List<String>> instancesByCluster(
            org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse response) {
        Map<String, List<String>> byCluster = new HashMap<>();
        for (var service : response.getData().getServicesList()) {
            List<String> ids = byCluster.computeIfAbsent(service.getCluster(), ignored -> new ArrayList<>());
            for (Instance instance : service.getInstances().getInstancesList()) {
                ids.add(instance.getInstanceId());
            }
        }
        return byCluster;
    }

    private StreamEvents subscribe(SubscribeRequest request) {
        StreamEvents events = new StreamEvents();
        DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel).subscribe(request, events);
        return events;
    }

    /** discovery ready = 查询非 UNAVAILABLE（全量门：两上游首轮 SYNCED 才放行）。 */
    private void awaitDiscoveryReady() {
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
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

    /** 订阅流收集器（事件/错误并发安全，描述串便于断言；模式同 DiscoverySubscriptionIT）。 */
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
            // 服务端不主动完成订阅流
        }

        List<String> descriptions() {
            List<String> out = new ArrayList<>();
            for (SubscriptionEvent event : received) {
                out.add(describe(event));
            }
            return out;
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
