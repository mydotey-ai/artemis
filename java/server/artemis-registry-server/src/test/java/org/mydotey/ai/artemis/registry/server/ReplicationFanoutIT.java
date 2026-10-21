package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 进程内 3 节点集群端到端（phase-2 副本租约语义）：任一节点写入全节点最终一致、RENEW 复制
 * 维持副本、停止心跳全节点各自清理、心跳 upsert 扇出重建、缺席 peer 退避重试后收敛、复制指标。
 * 真实时间驱动（小 TTL 配置），查询面经内部端口只读事实查询轮询断言。
 */
class ReplicationFanoutIT {

    private static final int NODE_COUNT = 3;
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";

    /** 短 TTL 场景值：过期/续约语义在秒级可观察（默认 30s 不适合 IT）。 */
    private static final String SHORT_TTL_MS = "2000";

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final Map<Integer, ManagedChannel> externalChannels = new HashMap<>();
    private final Map<Integer, ManagedChannel> peerChannels = new HashMap<>();

    @Test
    void should_converge_on_all_peers_when_instance_written_on_one_node() {
        Cluster cluster = startCluster(List.of());

        cluster.stub(0).register(registerRequest("i1", 100));
        awaitInstanceEverywhere(cluster, "i1");

        cluster.stub(0).register(registerRequest("i1", 200)); // register 无条件覆盖
        TestAwait.await(
                "update converges",
                () -> cluster.query(1).getData().getInstances(0).getWeight() == 200
                        && cluster.query(2).getData().getInstances(0).getWeight() == 200);

        cluster.stub(0).deregister(deregisterRequest("i1"));
        TestAwait.await(
                "deregister converges",
                () -> cluster.query(1).getData().getInstancesCount() == 0
                        && cluster.query(2).getData().getInstancesCount() == 0);
    }

    @Test
    void should_keep_replicas_alive_on_peers_when_client_heartbeats_steadily() throws Exception {
        Cluster cluster = startCluster(List.of("--artemis.registry.lease.ttlMs=" + SHORT_TTL_MS));

        cluster.stub(0).register(registerRequest("i1", 100));
        awaitInstanceEverywhere(cluster, "i1");

        // 稳态心跳（纯续约，内容不变）：RENEW 批量扇出维持 peer 副本跨多个 TTL 周期不消失
        for (long seq = 1; seq <= 4; seq++) {
            Heartbeats.collect(cluster.externalChannel(0), heartbeatRequest(seq, "i1"));
            Thread.sleep(700); // < TTL 2s
        }

        assertThat(cluster.query(1).getData().getInstancesCount())
                .as("peer replica kept alive by replicated RENEW")
                .isEqualTo(1);
        assertThat(cluster.query(2).getData().getInstancesCount()).isEqualTo(1);
    }

    @Test
    void should_clean_instance_on_all_nodes_when_client_stops_heartbeating() throws Exception {
        Cluster cluster = startCluster(List.of("--artemis.registry.lease.ttlMs=" + SHORT_TTL_MS));

        cluster.stub(0).register(registerRequest("i1", 100));
        awaitInstanceEverywhere(cluster, "i1");

        Thread.sleep(3_500); // 超过 TTL，无任何续约

        // 原产品继承：收不到续约的租约（事实源与副本同一种租约）在全集群各自过期清理，无残留
        for (int i = 0; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "lease expired on node-" + node,
                    () -> cluster.query(node).getData().getInstancesCount() == 0);
        }
    }

    @Test
    void should_rebuild_replica_when_renew_arrives_after_local_expiry() throws Exception {
        Cluster cluster = startCluster(List.of("--artemis.registry.lease.ttlMs=" + SHORT_TTL_MS));

        cluster.stub(0).register(registerRequest("i1", 100));
        awaitInstanceEverywhere(cluster, "i1");

        Thread.sleep(3_500); // 全节点本地清理（事实源与副本均过期）

        // 心跳 upsert 缺席自动注册 → 扇出 → peer 副本重建（分区恢复路径）
        Heartbeats.collect(cluster.externalChannel(0), heartbeatRequest(1L, "i1"));
        awaitInstanceEverywhere(cluster, "i1");
    }

    @Test
    void should_converge_after_peer_joins_when_peer_temporarily_absent() {
        List<Integer> ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        String members = membersString(ports);
        List<String> retry = List.of(
                "--artemis.registry.replication.retryInitialMs=100", "--artemis.registry.replication.retryMaxMs=500");

        // 节点 0、1 先行；节点 2 端口预留不启动（对它的投递持续退避重试，小退避参数）
        startNode(0, externalPort(ports, 0), peerPort(ports, 0), members, retry);
        startNode(1, externalPort(ports, 1), peerPort(ports, 1), members, retry);

        Cluster cluster = new Cluster(ports);
        cluster.stub(0).register(registerRequest("i1", 100));
        TestAwait.await(
                "converges on started peer", () -> cluster.query(1).getData().getInstancesCount() == 1);

        startNode(2, externalPort(ports, 2), peerPort(ports, 2), members, retry); // 缺席 peer 后起（空表 ready）
        TestAwait.await(
                "retry delivery converges after peer joins",
                () -> cluster.query(2).getData().getInstancesCount() == 1);
    }

    @Test
    void should_expose_replication_metrics_when_fanout_active() {
        Cluster cluster = startCluster(List.of());

        cluster.stub(0).register(registerRequest("i1", 100));
        awaitInstanceEverywhere(cluster, "i1");

        MeterRegistry node0 = contexts.get(0).getBean(MeterRegistry.class);
        MeterRegistry node1 = contexts.get(1).getBean(MeterRegistry.class);
        TestAwait.await(
                "sent metric recorded",
                () -> node0.get("artemis.registry.replication.batches.sent")
                                .tag("peer", "node-1")
                                .counter()
                                .count()
                        >= 1.0);
        assertThat(node1.get("artemis.registry.replication.batches.received")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(node1.get("artemis.registry.replication.applied.changes")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1.0);
    }

    // ---- 设施 ----

    private void awaitInstanceEverywhere(Cluster cluster, String instanceId) {
        for (int i = 1; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "instance visible on node-" + node,
                    () -> cluster.query(node).getData().getInstancesCount() == 1
                            && cluster.query(node)
                                    .getData()
                                    .getInstances(0)
                                    .getInstanceId()
                                    .equals(instanceId));
        }
    }

    /** 起满 3 节点集群（每节点外部端口 + peer 端口预分配）。 */
    private Cluster startCluster(List<String> extraArgs) {
        List<Integer> ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        String members = membersString(ports);
        for (int i = 0; i < NODE_COUNT; i++) {
            startNode(i, externalPort(ports, i), peerPort(ports, i), members, extraArgs);
        }
        return new Cluster(ports);
    }

    private ConfigurableApplicationContext startNode(
            int index, int externalPort, int peerPort, String members, List<String> extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.application.name=artemis-registry",
                "--spring.main.web-application-type=none",
                "--spring.grpc.server.port=" + externalPort,
                "--management.server.port=0",
                "--artemis.registry.internal.port=" + peerPort,
                "--artemis.registry.internal.address=127.0.0.1",
                "--artemis.registry.cluster.members=" + members,
                "--artemis.registry.cluster.self=" + nodeId(index),
                "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + index + ".bin")));
        args.addAll(extraArgs);
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ArtemisRegistryServer.class).run(args.toArray(String[]::new));
        contexts.add(context);
        // 集群模式启动数据源异步执行：等待 ready 再驱动写入/查询（外部写与查询均受 readiness 门控）
        TestAwait.await(
                "node-" + index + " ready",
                () -> context.getBean(org.mydotey.ai.artemis.registry.application.ReadinessGate.class)
                        .isReady());
        return context;
    }

    private static String nodeId(int index) {
        return "node-" + index;
    }

    /** reservePorts 的偶数位作外部端口、奇数位作 peer 端口。 */
    private static String membersString(List<Integer> ports) {
        return IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(ports, i) + "@127.0.0.1:" + externalPort(ports, i))
                .collect(Collectors.joining(","));
    }

    private static int peerPort(List<Integer> ports, int index) {
        return ports.get(index * 2 + 1);
    }

    private static int externalPort(List<Integer> ports, int index) {
        return ports.get(index * 2);
    }

    /** 集群句柄：外部端口驱动写（channel 按节点缓存）、内部端口只读查询。 */
    private final class Cluster {

        private final List<Integer> ports;

        private Cluster(List<Integer> ports) {
            this.ports = ports;
        }

        private ManagedChannel externalChannel(int index) {
            return externalChannels.computeIfAbsent(
                    index,
                    ignored -> ManagedChannelBuilder.forAddress("127.0.0.1", externalPort(ports, index))
                            .usePlaintext()
                            .build());
        }

        private RegistryServiceGrpc.RegistryServiceBlockingStub stub(int index) {
            return RegistryServiceGrpc.newBlockingStub(externalChannel(index))
                    .withInterceptors(AuthClients.authenticated());
        }

        /** 内部端口只读查询（内部认证默认关闭，IT 不配凭证）。 */
        private GetServiceInstancesResponse query(int index) {
            ManagedChannel channel = peerChannels.computeIfAbsent(
                    index,
                    ignored -> ManagedChannelBuilder.forAddress("127.0.0.1", peerPort(ports, index))
                            .usePlaintext()
                            .build());
            return RegistryQueryServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getServiceInstances(GetServiceInstancesRequest.newBuilder()
                            .setKey(serviceKey())
                            .build());
        }
    }

    private static RegisterRequest registerRequest(String instanceId, int weight) {
        return RegisterRequest.newBuilder()
                .addServices(ServiceInstances.newBuilder()
                        .setKey(serviceKey())
                        .addInstances(Instance.newBuilder()
                                .setInstanceId(instanceId)
                                .setHost("10.0.0.1")
                                .setPort(8080)
                                .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                                .setWeight(weight)))
                .build();
    }

    private static HeartbeatRequest heartbeatRequest(long seq, String... instanceIds) {
        HeartbeatRequest.Builder request = HeartbeatRequest.newBuilder().setSeq(seq);
        for (String instanceId : instanceIds) {
            request.addServices(ServiceInstances.newBuilder()
                    .setKey(serviceKey())
                    .addInstances(Instance.newBuilder()
                            .setInstanceId(instanceId)
                            .setHost("10.0.0.1")
                            .setPort(8080)
                            .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                            .setWeight(100)));
        }
        return request.build();
    }

    private static DeregisterRequest deregisterRequest(String instanceId) {
        return DeregisterRequest.newBuilder()
                .addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                        .setService(serviceKey())
                        .setInstanceId(instanceId))
                .build();
    }

    private static ServiceKey.Builder serviceKey() {
        return ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ConfigurableApplicationContext context : contexts) {
            context.close();
        }
        contexts.clear();
        for (ManagedChannel channel : externalChannels.values()) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        externalChannels.clear();
        for (ManagedChannel channel : peerChannels.values()) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        peerChannels.clear();
    }
}
