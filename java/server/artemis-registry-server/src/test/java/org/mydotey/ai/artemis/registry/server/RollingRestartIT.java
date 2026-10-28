package org.mydotey.ai.artemis.registry.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 3 节点集群滚动重启（v1.0 phase-5 滚动升级·语义层，验收「集群滚动升级无感」）：
 * 逐节点停机 → 同配置同端口重启（存在已就绪 peer → bootstrap 全量同步），滚动全程
 * 断言——存活节点注册表完整、evictExpired 误摘探针不减（续约链未断）、重启节点
 * ready 后数据恢复、终态三节点一致且 SDK 实例仍注册（心跳断流换址续约，不断流不换数据）。
 * 两停机形态：优雅停机滚动（drain 排水序列）与无排水滚动（drainMs=0，模拟滚动升级中
 * Pod 被强终止的最近似——进程内 close 仍走 DOWN 自报与终刷快照，非真进程崩溃；其增量
 * 在无排水窗口，无快照恢复路径由 SnapshotRestoreIT 单独覆盖）。协议层兼容（新旧消息互读）
 * 由 proto-compat-check.sh 与 ProtoForwardCompatIT 承担。
 */
class RollingRestartIT {

    private static final int NODE_COUNT = 3;
    private static final int INSTANCE_COUNT = 2;
    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();
    private static final ServiceKey DOMAIN_KEY = new ServiceKey("default", "order-service");

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    /** bean 在 await 循环外缓存：循环内 getBean 可能被 Spring 单例锁阻塞绕过超时（同 SdkFailoverIT caution）。 */
    private final List<RegistryService> services = new ArrayList<>();

    private final List<ReadinessGate> readinessGates = new ArrayList<>();
    private List<Integer> ports;
    private String members;
    private String drainMs;
    private RegistryClient client;

    @Test
    void should_keepRegistryServingAndSdkAlive_when_rollingGracefulRestart() {
        startCluster("500");
        awaitAllReady();
        startClient();
        registerInstances();
        rollAllNodes("graceful stop");
    }

    @Test
    void should_keepDataConsistent_when_rollingRestartUndrained() {
        startCluster("0");
        awaitAllReady();
        startClient();
        registerInstances();
        rollAllNodes("undrained stop");
    }

    private void rollAllNodes(String stopMode) {
        for (int node = 0; node < NODE_COUNT; node++) {
            // 无排水（drainMs=0）：断流即换址信号；close 仍走优雅序列（DOWN 自报 + 终刷），
            // 真进程崩溃的增量（无终刷恢复）由 SnapshotRestoreIT 覆盖
            contexts.get(node).close();
            assertSurvivorsConsistent("after " + stopMode + " node-" + node, node);
            contexts.set(node, startNode(node));
            cacheBeans(node);
            assertNodeRecovered(node);
        }
        assertFinalState();
    }

    // ---- 断言探针 ----

    /** 滚动窗口断言：存活节点注册表完整且续约链未断（清理探针不误摘）。 */
    private void assertSurvivorsConsistent(String when, int stopped) {
        List<Integer> survivors =
                IntStream.range(0, NODE_COUNT).filter(i -> i != stopped).boxed().collect(Collectors.toList());
        TestAwait.await("survivors keep full registry " + when, () -> {
            for (int node : survivors) {
                registryService(node).evictExpired();
                if (instanceCount(node) != INSTANCE_COUNT) {
                    return false;
                }
            }
            return true;
        });
    }

    /** 重启节点恢复：ready（bootstrap 从存活 peer 同步）+ 数据完整。 */
    private void assertNodeRecovered(int node) {
        ReadinessGate gate = readinessGates.get(node);
        TestAwait.await("restarted node-" + node + " ready", 60_000, gate::isReady);
        TestAwait.await("restarted node-" + node + " synced", () -> instanceCount(node) == INSTANCE_COUNT);
    }

    /**
     * 终态：清理探针跑满一个超过租约 TTL 的窗口（ttlMs=3000，探针窗 4.5s）且三节点全程一致——
     * 滚动后 SDK 心跳持续续约（断流已换址恢复），心跳若停则 TTL 内即被探针清理暴露。
     */
    private void assertFinalState() {
        long surviveUntil = System.currentTimeMillis() + 4500;
        TestAwait.await("instances survive beyond lease ttl after rolling", () -> {
            for (int node = 0; node < NODE_COUNT; node++) {
                registryService(node).evictExpired();
                if (instanceCount(node) != INSTANCE_COUNT) {
                    return false;
                }
            }
            return System.currentTimeMillis() >= surviveUntil;
        });
    }

    // ---- 设施（复用 SdkFailoverIT / GracefulShutdownIT 模式） ----

    private void startCluster(String drainMs) {
        this.drainMs = drainMs;
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 3);
        members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(i) + "@127.0.0.1:" + grpcPort(i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(startNode(i));
            services.add(null);
            readinessGates.add(null);
            cacheBeans(i);
        }
        // SDK 前置寻址：只配 node-0 外部 HTTP（经 /cluster/nodes 发现全集群）——客户端属性，循环外一次设置
        System.setProperty("artemis.client.registry.addresses", "http://127.0.0.1:" + httpPort(0));
    }

    private ConfigurableApplicationContext startNode(int index) {
        return new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.application.name=artemis-registry",
                        "--spring.grpc.server.port=" + grpcPort(index),
                        "--server.port=" + httpPort(index),
                        "--management.server.port=0",
                        "--artemis.registry.lease.ttlMs=3000",
                        "--artemis.registry.internal.port=" + peerPort(index),
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.cluster.members=" + members,
                        "--artemis.registry.cluster.self=" + nodeId(index),
                        "--artemis.registry.cluster.probeIntervalMs=200",
                        "--artemis.registry.cluster.probeTimeoutMs=1000",
                        "--artemis.registry.shutdown.drainMs=" + drainMs,
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + index + ".bin"));
    }

    private void startClient() {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.registry.connection.ttlMs", "0"); // 禁轮换：换址仅由断流触发，聚焦滚动窗口
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "artemis");
        client = ArtemisClients.newRegistryClient();
        client.start();
    }

    private void registerInstances() {
        client.register(instance("i-1"));
        client.register(instance("i-2"));
        TestAwait.await("instances replicated to all nodes", () -> {
            for (int node = 0; node < NODE_COUNT; node++) {
                if (instanceCount(node) != INSTANCE_COUNT) {
                    return false;
                }
            }
            return true;
        });
    }

    private void cacheBeans(int index) {
        ConfigurableApplicationContext context = contexts.get(index);
        services.set(index, context.getBean(RegistryService.class));
        readinessGates.set(index, context.getBean(ReadinessGate.class));
    }

    private RegistryService registryService(int index) {
        return services.get(index);
    }

    private int instanceCount(int index) {
        return registryService(index)
                .getInstances(DOMAIN_KEY)
                .map(s -> s.instances().size())
                .orElse(0);
    }

    private void awaitAllReady() {
        for (int i = 0; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "node-" + node + " ready", () -> readinessGates.get(node).isReady());
        }
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

    private int grpcPort(int index) {
        return ports.get(index * 3);
    }

    private int peerPort(int index) {
        return ports.get(index * 3 + 1);
    }

    private int httpPort(int index) {
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
