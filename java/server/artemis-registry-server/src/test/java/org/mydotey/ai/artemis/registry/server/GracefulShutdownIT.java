package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.ReplicationService;
import org.mydotey.ai.artemis.registry.domain.ClusterMemberStatus;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 优雅停机集群语义（v0.2 phase-6 验收）：context close 序列 = 标记 DOWN → 排水 → 终刷 → 停端口。
 * 排水窗口内：peer 观测 DOWN、/readyz 503、已建心跳流继续续约；停端口后客户端凭容灾无感换址；
 * 终刷快照重启回放（单节点）与 peer bootstrap 恢复（集群）两条重启路径收敛。
 */
class GracefulShutdownIT {

    private static final int NODE_COUNT = 3;
    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();
    private static final ServiceKey DOMAIN_KEY = new ServiceKey("default", "order-service");

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private List<Integer> ports;
    private String members;
    private ArtemisClient client;

    @Test
    void should_refuseNewTrafficButServeEstablishedStreams_when_draining() throws Exception {
        startCluster("5000", "1000");
        awaitAllReady();
        startClient();
        client.register(instance("i-1"));

        int connected = awaitHeartbeatNode();
        RegistryService connectedService = contexts.get(connected).getBean(RegistryService.class);
        String connectedNodeId = nodeId(connected);
        List<Integer> survivors = survivorIndexes(connected);

        // 异步优雅停机：drainMs=5000 提供取证窗口
        Thread closer = new Thread(() -> shutdownNode(connected), "graceful-shutdown-node");
        closer.start();

        // peer 观测 DOWN（自报经后台探测传播，probeIntervalMs=200）
        TestAwait.await(
                "peer observes DOWN during drain",
                5_000,
                () -> survivors.stream().allMatch(node -> peerViewOf(node, connectedNodeId) == NodeState.DOWN));
        // 未就绪摘流量：/readyz 503（无认证白名单）
        assertThatReadyzIs503(connected);
        // 已建心跳流完整服务：drain 窗口内续约不断，实例在停机节点不过期
        connectedService.evictExpired();
        TestAwait.await("established stream still renewing during drain", () -> instanceCount(connectedService) == 1);

        closer.join(20_000);
        assertThat(closer.isAlive()).isFalse(); // 停机序列（drain + 终刷 + 停端口）须在窗口内完成
        assertThat(contexts.get(connected)).isNull();
    }

    @Test
    void should_failover_when_connectedNodeShutdownGracefully() {
        startCluster("0", "1000");
        awaitAllReady();
        startClient();
        client.register(instance("i-1"));

        int connected = awaitHeartbeatNode();
        shutdownNode(connected);

        // 无感换址：实例在存活节点持续可见且不过期（续约已到达新节点）
        List<Integer> survivors = survivorIndexes(connected);
        TestAwait.await("instance alive on survivors after failover", () -> {
            for (int node : survivors) {
                registryService(node).evictExpired();
                if (instanceCount(registryService(node)) != 1) {
                    return false;
                }
            }
            return true;
        });
        // 心跳已到达新节点（换址而非停跳）
        TestAwait.await("heartbeat reached a new node", () -> survivors.stream().anyMatch(this::hasHeartbeat));
    }

    @Test
    void should_replayFinalSnapshot_when_restartedAfterShutdown() {
        List<Integer> singlePorts = TestGrpcPorts.reservePorts(2);
        Path snapshot = tempDir.resolve("single-node-snapshot.bin");

        ConfigurableApplicationContext first = startSingleNode(singlePorts, snapshot);
        RegistryService service = first.getBean(RegistryService.class);
        service.register(domainRequest("i-1"));
        assertThatInstanceCount(first, 1);

        first.close(); // 优雅停机（drainMs=0）：终刷快照是唯一落盘（周期调度默认 5min 未到）

        // 同快照文件重起（单节点分支②）：终刷数据回放恢复；新端口避免复用 bind
        ConfigurableApplicationContext second = startSingleNode(TestGrpcPorts.reservePorts(2), snapshot);
        try {
            assertThatInstanceCount(second, 1);
        } finally {
            second.close();
        }
    }

    @Test
    void should_converge_when_nodeRestartedAfterShutdown() {
        startCluster("0", "30000"); // 长租约：无客户端维持心跳，跨停机重启窗口不过期
        awaitAllReady();
        registryService(0).register(domainRequest("i-1"));

        // 复制副本先行：停机前 peer 已有全量数据
        TestAwait.await(
                "replicated to all nodes before shutdown",
                () -> IntStream.range(0, NODE_COUNT).allMatch(node -> instanceCount(registryService(node)) == 1));

        shutdownNode(1);
        assertThatInstanceCount(contexts.get(0), 1);
        assertThatInstanceCount(contexts.get(2), 1);

        // 重启 node-1（同配置）：存在已就绪 peer → bootstrap 全量同步 → ready 后全集群一致
        contexts.set(1, startNode(1));
        TestAwait.await(
                "restarted node ready and converged",
                () -> IntStream.range(0, NODE_COUNT).allMatch(node -> instanceCount(registryService(node)) == 1));
    }

    // ---- 设施 ----

    private void startCluster(String drainMs, String ttlMs) {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 3);
        members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(ports, i) + "@127.0.0.1:" + grpcPort(ports, i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(startNode(i, drainMs, ttlMs));
        }
        // SDK 前置寻址：只配 node-0 外部 HTTP（经 /cluster/nodes 发现全集群）
        System.setProperty("artemis.client.registry.addresses", "http://127.0.0.1:" + httpPort(ports, 0));
    }

    private ConfigurableApplicationContext startNode(int index) {
        return startNode(index, "0", "30000");
    }

    private ConfigurableApplicationContext startNode(int index, String drainMs, String ttlMs) {
        return new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.application.name=artemis-registry",
                        "--spring.grpc.server.port=" + grpcPort(ports, index),
                        "--server.port=" + httpPort(ports, index),
                        "--management.server.port=0",
                        "--artemis.registry.lease.ttlMs=" + ttlMs,
                        "--artemis.registry.internal.port=" + peerPort(ports, index),
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.cluster.members=" + members,
                        "--artemis.registry.cluster.self=" + nodeId(index),
                        "--artemis.registry.cluster.probeIntervalMs=200",
                        "--artemis.registry.cluster.probeTimeoutMs=1000",
                        "--artemis.registry.shutdown.drainMs=" + drainMs,
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + index + ".bin"));
    }

    private ConfigurableApplicationContext startSingleNode(List<Integer> singlePorts, Path snapshot) {
        return new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.application.name=artemis-registry",
                        "--spring.grpc.server.port=" + singlePorts.get(0),
                        "--server.port=" + singlePorts.get(1),
                        "--management.server.port=0",
                        "--artemis.registry.lease.ttlMs=30000",
                        "--artemis.registry.snapshot.path=" + snapshot);
    }

    private void startClient() {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.registry.connection.ttlMs", "0"); // 聚焦停机换址，禁轮换
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "artemis");
        client = ArtemisClients.newClient();
        client.start();
    }

    private void awaitAllReady() {
        for (int i = 0; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "node-" + node + " ready",
                    () -> contexts.get(node).getBean(ReadinessGate.class).isReady());
        }
    }

    /** 等任一节点收到心跳（前置寻址完成 + 开流），返回该节点下标。 */
    private int awaitHeartbeatNode() {
        TestAwait.await(
                "heartbeat reached a node", () -> IntStream.range(0, NODE_COUNT).anyMatch(this::hasHeartbeat));
        return IntStream.range(0, NODE_COUNT)
                .filter(this::hasHeartbeat)
                .findFirst()
                .orElseThrow();
    }

    private boolean hasHeartbeat(int node) {
        MeterRegistry registry = contexts.get(node).getBean(MeterRegistry.class);
        return registry.find("artemis.registry.heartbeat.instances").counter() != null
                && registry.find("artemis.registry.heartbeat.instances")
                                .counter()
                                .count()
                        > 0;
    }

    /** 经旁观节点的节点状态视图观测目标节点态（peer 探测传播）。 */
    private NodeState peerViewOf(int observer, String targetNodeId) {
        return contexts.get(observer).getBean(ReplicationService.class).clusterNodes().stream()
                .filter(member -> member.member().nodeId().equals(targetNodeId))
                .map(ClusterMemberStatus::state)
                .findFirst()
                .orElse(NodeState.UNKNOWN);
    }

    private void assertThatReadyzIs503(int node) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:" + httpPort(ports, node) + "/readyz")
                        .toURL()
                        .openConnection();
        try {
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(2_000);
            assertThat(connection.getResponseCode()).isEqualTo(503);
        } finally {
            connection.disconnect();
        }
    }

    private void shutdownNode(int index) {
        ConfigurableApplicationContext context = contexts.get(index);
        contexts.set(index, null);
        context.close();
    }

    private List<Integer> survivorIndexes(int killed) {
        return IntStream.range(0, NODE_COUNT).filter(i -> i != killed).boxed().collect(Collectors.toList());
    }

    private RegistryService registryService(int index) {
        return contexts.get(index).getBean(RegistryService.class);
    }

    private void assertThatInstanceCount(ConfigurableApplicationContext context, int expected) {
        int count = instanceCount(context.getBean(RegistryService.class));
        if (count != expected) {
            throw new AssertionError("expected " + expected + " instance(s) but was " + count);
        }
    }

    private int instanceCount(RegistryService service) {
        return service.getInstances(DOMAIN_KEY).map(s -> s.instances().size()).orElse(0);
    }

    private RegisterRequest domainRequest(String instanceId) {
        return new RegisterRequest(List.of(new ServiceInstances(
                DOMAIN_KEY, List.of(new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of())))));
    }

    private static ServiceInstance instance(String instanceId) {
        return ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId(instanceId)
                .host("10.0.0.1")
                .port(8080)
                .weight(100)
                .build();
    }

    private static String nodeId(int index) {
        return "node-" + index;
    }

    private static int grpcPort(List<Integer> ports, int index) {
        return ports.get(index * 3);
    }

    private static int peerPort(List<Integer> ports, int index) {
        return ports.get(index * 3 + 1);
    }

    private static int httpPort(List<Integer> ports, int index) {
        return ports.get(index * 3 + 2);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        System.clearProperty("artemis.client.registry.addresses");
        System.clearProperty("artemis.client.heartbeat.intervalMs");
        System.clearProperty("artemis.client.registry.connection.ttlMs");
        System.clearProperty("artemis.client.auth.username");
        System.clearProperty("artemis.client.auth.password");
        for (ConfigurableApplicationContext context : contexts) {
            if (context != null) {
                context.close();
            }
        }
        contexts.clear();
    }
}
