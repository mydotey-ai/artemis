package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 对账与新鲜度端到端（v0.3 phase-4，ADR-019 三件套之三）：进程内真实 registry + discovery，
 * 测试侧以 SDK 角色独立实现 canonical checksum 规则（D-F2，照 api 文档手写、不引服务端类）
 * 驱动「表拉取 ↔ 本地比对 ↔ 不一致触发全量拉」闭环；registry 关停/重启验证断连标记与
 * 轮询自愈。
 */
class DiscoveryReconciliationIT {

    private static final ServiceKey ORDER = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("order-service")
            .build();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private int registrySnapshotGeneration;
    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;
    private int registryPeerPort;

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
    void should_expose_checksum_table_consistent_with_query_view() throws Exception {
        startRegistryAndDiscovery();
        register(ORDER, instance("i1", 8080));
        awaitProjected(1);

        Map<ServiceKey, Integer> table = checksumTable(Map.of());
        assertThat(table).containsKey(ORDER);
        assertThat(table.get(ORDER)).as("SDK 角色独立重算一致").isEqualTo(sdkChecksum(queryInstances(ORDER, Map.of())));

        int before = table.get(ORDER);
        update(ORDER, instance("i1", 9090)); // 内容变化 → checksum 变
        awaitUntil("checksum changed on update", () -> checksumTable(Map.of()).get(ORDER) != before);
        assertThat(checksumTable(Map.of()).get(ORDER)).isEqualTo(sdkChecksum(queryInstances(ORDER, Map.of())));

        deregister(ORDER, "i1"); // 实例清空 → 条目消失（缺项 = 权威空，D-F1）
        awaitUntil(
                "entry gone after last instance deleted",
                () -> !checksumTable(Map.of()).containsKey(ORDER));
    }

    @Test
    void should_detect_local_view_tamper_via_checksum_and_recover_by_query() throws Exception {
        startRegistryAndDiscovery();
        register(ORDER, instance("i1", 8080));
        register(ORDER, instance("i2", 8080));
        awaitProjected(2);

        // SDK 视角：本地视图漏记 i2（模拟增量丢失）
        List<Instance> tampered = new ArrayList<>();
        for (Instance instance : queryInstances(ORDER, Map.of())) {
            if ("i1".equals(instance.getInstanceId())) {
                tampered.add(instance);
            }
        }
        Integer server = checksumTable(Map.of()).get(ORDER);
        assertThat(sdkChecksum(tampered)).as("丢失 NEW 后本地比对必不一致").isNotEqualTo(server);

        // 全量拉收敛：本地重建 = 服务端表一致（对账闭环）
        assertThat(sdkChecksum(queryInstances(ORDER, Map.of()))).isEqualTo(server);
    }

    @Test
    void should_mark_upstream_disconnected_in_pulls_while_serving_stale_data() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);
        awaitDiscoveryReady();
        register(ORDER, instance("i1", 8080));
        awaitProjected(1);

        closeRegistryChannel();
        registryContext.close();
        registryContext = null;

        // 断连标记可见（拉取面）且数据继续服务（陈旧换可用，D-D5）
        awaitUntil(
                "upstream marked disconnected",
                () -> queryUpstreamState().equals(UpstreamSyncState.UPSTREAM_SYNC_STATE_DISCONNECTED));
        assertThat(queryInstanceIds(ORDER, Map.of())).containsExactly("i1");

        // registry 重启（独立快照目录 = 空表）：重连覆盖 → 拉/表两面收敛（轮询自愈）
        startRegistry(registryPort, peerPort);
        awaitUntil(
                "resynced after restart",
                () -> queryUpstreamState().equals(UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED));
        register(ORDER, instance("i2", 8080));
        awaitProjected(1);
        assertThat(queryInstanceIds(ORDER, Map.of())).containsExactly("i2");
        assertThat(checksumTable(Map.of()).get(ORDER)).isEqualTo(sdkChecksum(queryInstances(ORDER, Map.of())));
    }

    @Test
    void should_compute_labels_view_checksum_end_to_end() throws Exception {
        startRegistryAndDiscovery();
        register(ORDER, instance("i1", 8080, "env", "prod"));
        register(ORDER, instance("i2", 8080, "env", "dev"));
        awaitProjected(2);

        Map<ServiceKey, Integer> table = checksumTable(Map.of("env", "prod"));
        assertThat(table).as("labels 视图只含有实例条目").containsOnlyKeys(ORDER);
        assertThat(table.get(ORDER)).isEqualTo(sdkChecksum(queryInstances(ORDER, Map.of("env", "prod"))));

        // 全量视图（无 labels）与 labels 视图指纹不同（确为过滤视图）
        Map<ServiceKey, Integer> all = checksumTable(Map.of());
        assertThat(all.get(ORDER)).isNotEqualTo(table.get(ORDER));
        assertThat(all.get(ORDER)).isEqualTo(sdkChecksum(queryInstances(ORDER, Map.of())));
    }

    @Test
    void should_reject_checksum_before_first_sync() throws Exception {
        startDiscovery(freePort()); // 上游无人监听
        DiscoveryReconciliationServiceGrpc.DiscoveryReconciliationServiceBlockingStub stub =
                DiscoveryReconciliationServiceGrpc.newBlockingStub(discoveryChannel);
        assertThatThrownBy(() -> stub.checksum(ChecksumRequest.newBuilder().build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    }

    // ---- SDK 角色：canonical checksum 独立实现（D-F2，照 api 文档手写，不引服务端类）----

    private static int sdkChecksum(List<Instance> instances) {
        int checksum = 0;
        for (Instance instance : instances) {
            String statusName = instance.getStatus().name().replace("INSTANCE_STATUS_", "");
            StringBuilder canonical = new StringBuilder(64);
            canonical
                    .append(instance.getInstanceId())
                    .append('\u0000')
                    .append(instance.getHost())
                    .append('\u0000')
                    .append(instance.getPort())
                    .append('\u0000')
                    .append(statusName)
                    .append('\u0000')
                    .append(instance.getWeight())
                    .append('\u0000');
            int metadata = 0;
            for (Map.Entry<String, String> entry : instance.getMetadataMap().entrySet()) {
                metadata ^= fnv1a32(entry.getKey() + '\u0000' + entry.getValue() + '\u0000');
            }
            checksum ^= fnv1a32(canonical.toString()) ^ metadata;
        }
        return checksum;
    }

    private static int fnv1a32(String value) {
        int hash = 0x811c9dc5;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }

    // ---- 起停与驱动（模式同 DiscoverySubscriptionIT）----

    private void startRegistryAndDiscovery() throws IOException {
        startRegistry(freePort(), freePort());
        startDiscovery(registryPeerPort);
        awaitDiscoveryReady();
    }

    private void awaitProjected(int expectedInstances) {
        awaitUntil("projected " + expectedInstances, () -> {
            int count = 0;
            for (var service : queryStub()
                    .query(QueryRequest.newBuilder().build())
                    .getData()
                    .getServicesList()) {
                count += service.getInstances().getInstancesCount();
            }
            return count >= expectedInstances;
        });
    }

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

    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub() {
        return DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
    }

    private Map<ServiceKey, Integer> checksumTable(Map<String, String> labels) {
        ChecksumResponse response = DiscoveryReconciliationServiceGrpc.newBlockingStub(discoveryChannel)
                .checksum(ChecksumRequest.newBuilder().putAllLabels(labels).build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        Map<ServiceKey, Integer> table = new HashMap<>();
        for (var cluster : response.getData().getClustersList()) {
            for (var service : cluster.getServicesList()) {
                table.put(service.getKey(), service.getChecksum());
            }
        }
        return table;
    }

    private UpstreamSyncState queryUpstreamState() {
        for (var upstream :
                queryStub().query(QueryRequest.newBuilder().build()).getData().getUpstreamsList()) {
            if ("test-cluster".equals(upstream.getCluster())) {
                return upstream.getState();
            }
        }
        return null;
    }

    private List<Instance> queryInstances(ServiceKey key, Map<String, String> labels) {
        List<Instance> instances = new ArrayList<>();
        for (var service : queryStub()
                .query(QueryRequest.newBuilder()
                        .addServices(key)
                        .putAllLabels(labels)
                        .build())
                .getData()
                .getServicesList()) {
            instances.addAll(service.getInstances().getInstancesList());
        }
        return instances;
    }

    private List<String> queryInstanceIds(ServiceKey key, Map<String, String> labels) {
        return queryInstances(key, labels).stream().map(Instance::getInstanceId).toList();
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
        awaitUntil("discovery ready", () -> {
            try {
                queryStub().query(QueryRequest.newBuilder().build());
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
}
