package org.mydotey.ai.artemis.registry.server;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * SDK 三级地址容灾集群语义（v0.2 phase-5 验收）：3 节点集群 + 真实 SDK（HTTP 前置寻址只配
 * node-0 外部 HTTP 端口，节点列表经 /cluster/nodes 发现全集群）。杀当前连接节点 → 客户端
 * 无感换址并续约（实例在存活节点持续可见、不过期）；TTL 轮换压短 → 心跳遍历全部节点、
 * 各节点数据一致（轮换即分布式对账，ADR-006）。客户端连接节点经各节点
 * artemis.registry.heartbeat.instances 指标观测。
 */
class SdkFailoverIT {

    private static final int NODE_COUNT = 3;
    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();
    private static final ServiceKey DOMAIN_KEY = new ServiceKey("default", "order-service");

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private ArtemisClient client;

    @Test
    void should_failoverAndKeepLeaseAlive_when_connectedNodeKilled() {
        startCluster();
        awaitAllReady();
        startClient("0"); // TTL 轮换禁用，聚焦断流换址路径
        client.register(instance("i-1"));

        int connected = awaitHeartbeatNode();
        killNode(connected);

        // 无感换址：实例在存活节点持续可见且不被过期清理（续约已到达新节点）
        List<Integer> survivors = IntStream.range(0, NODE_COUNT)
                .filter(i -> i != connected)
                .boxed()
                .collect(Collectors.toList());
        TestAwait.await("instance alive on survivors after failover", () -> {
            for (int node : survivors) {
                registryService(node).evictExpired();
                if (instanceCount(registryService(node)) != 1) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void should_reconcileNodeData_when_ttlRotation() {
        startCluster();
        awaitAllReady();
        List<MeterRegistry> registries = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> contexts.get(i).getBean(MeterRegistry.class))
                .toList(); // 轮询前缓存：await 循环内 getBean 可能被 Spring 单例锁阻塞绕过超时
        startClient("1000"); // TTL 压短：轮换高频发生
        client.register(instance("i-1"));

        // 轮换换址有效：心跳到达 ≥2 个不同节点（轮换在换址而非同址重开）；「全节点覆盖」由
        // RotationStressTest（低负载单测）承担——高负载 IT 下随机全节点覆盖的尾延迟不可靠
        TestAwait.await(
                "heartbeat reached at least two nodes via rotation",
                60_000,
                () -> registries.stream()
                                .map(registry -> registry.find("artemis.registry.heartbeat.instances")
                                        .counter()
                                        .count())
                                .filter(count -> count > 0)
                                .count()
                        >= 2);
        // 轮换即对账（正确性）：无论当前连谁，各节点注册表经心跳 upsert + 复制扇出保持一致
        TestAwait.await(
                "all nodes consistent",
                30_000,
                () -> IntStream.range(0, NODE_COUNT).allMatch(node -> instanceCount(registryService(node)) == 1));
    }

    // ---- 设施 ----

    private void startCluster() {
        List<Integer> ports = TestGrpcPorts.reservePorts(NODE_COUNT * 3);
        String members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(ports, i) + "@127.0.0.1:" + grpcPort(ports, i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(new SpringApplicationBuilder(ArtemisRegistryServer.class)
                    .run(
                            "--spring.application.name=artemis-registry",
                            "--spring.grpc.server.port=" + grpcPort(ports, i),
                            "--server.port=" + httpPort(ports, i),
                            "--management.server.port=0",
                            "--artemis.registry.lease.ttlMs=1000",
                            "--artemis.registry.internal.port=" + peerPort(ports, i),
                            "--artemis.registry.internal.address=127.0.0.1",
                            "--artemis.registry.cluster.members=" + members,
                            "--artemis.registry.cluster.self=" + nodeId(i),
                            "--artemis.registry.cluster.probeIntervalMs=200",
                            "--artemis.registry.cluster.probeTimeoutMs=1000",
                            "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + i + ".bin")));
        }
        // SDK 前置寻址：只配 node-0 外部 HTTP（经 /cluster/nodes 发现全集群）——客户端属性，循环外一次设置
        System.setProperty("artemis.client.registry.addresses", "http://127.0.0.1:" + httpPort(ports, 0));
    }

    private void startClient(String ttlMs) {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.registry.connection.ttlMs", ttlMs);
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

    private void killNode(int index) {
        ConfigurableApplicationContext context = contexts.get(index);
        contexts.set(index, null);
        context.close();
    }

    private RegistryService registryService(int index) {
        return contexts.get(index).getBean(RegistryService.class);
    }

    private int instanceCount(RegistryService service) {
        return service.getInstances(DOMAIN_KEY).map(s -> s.instances().size()).orElse(0);
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
