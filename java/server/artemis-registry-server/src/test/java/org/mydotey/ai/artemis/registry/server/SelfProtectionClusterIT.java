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
import org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 进程内 3 节点自我保护集群语义：各节点只按本地收到的心跳独立判定；节点级兜底由高续约
 * keep-alive 服务维持健康，从而观察纯服务级隔离；短 maxCountResetMs 验证超时回落与陈旧清理。
 */
class SelfProtectionClusterIT {

    private static final int NODE_COUNT = 3;
    private static final String NAMESPACE = "default";
    private static final String KEEP_ALIVE = "keep-alive";
    private static final int KEEP_ALIVE_INSTANCES = 100;
    private static final String SHORT_TTL_MS = "3000";

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final Map<Integer, ManagedChannel> externalChannels = new HashMap<>();
    private final Map<Integer, ManagedChannel> peerChannels = new HashMap<>();

    @Test
    void should_judge_protection_independently_per_node_when_renewals_drop() throws Exception {
        Cluster cluster = startCluster("2", "60000");
        ServiceKey a = serviceKey("svc-a");
        ServiceKey b = serviceKey("svc-b");

        heartbeat(cluster, 0, a, List.of("a1"));
        heartbeat(cluster, 1, b, List.of("b1"));

        // 持续高续约 keep-alive：节点级窗口保持健康，隔离出纯服务级判定
        long seq = 1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            heartbeat(cluster, 0, serviceKey(KEEP_ALIVE), keepAliveInstances());
            heartbeat(cluster, 1, serviceKey(KEEP_ALIVE), keepAliveInstances());
            seq++;
            Thread.sleep(300);
        }

        // node-0 只保护 A；node-1 只保护 B；node-2 无本地心跳窗口，均不保护
        TestAwait.await("node-0 protects only A", () -> protectingOnly(cluster, 0, "svc-a"));
        TestAwait.await("node-1 protects only B", () -> protectingOnly(cluster, 1, "svc-b"));
        TestAwait.await(
                "node-2 protects nothing",
                () -> protectionStatus(cluster, 2).getData().getProtectingServices() == 0);

        // 服务级权威：受保护节点保留租约，其他节点副本正常过期（复制有重试/延迟，等收敛）
        TestAwait.await("node-0 keeps A", () -> instances(cluster, 0, a).size() == 1);
        TestAwait.await(
                "node-0 expires B replica", () -> instances(cluster, 0, b).isEmpty());
        TestAwait.await("node-1 keeps B", () -> instances(cluster, 1, b).size() == 1);
        TestAwait.await(
                "node-1 expires A replica", () -> instances(cluster, 1, a).isEmpty());
        TestAwait.await(
                "node-2 expires A replica", () -> instances(cluster, 2, a).isEmpty());
        TestAwait.await(
                "node-2 expires B replica", () -> instances(cluster, 2, b).isEmpty());

        // 指标：只有本地保护中的服务带标签，聚合计数正确
        MeterRegistry node0 = contexts.get(0).getBean(MeterRegistry.class);
        assertThat(node0.get("artemis.registry.self.service.protection")
                        .tag("namespace", NAMESPACE)
                        .tag("service", "svc-a")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(node0.find("artemis.registry.self.service.protection")
                        .tag("namespace", NAMESPACE)
                        .tag("service", "svc-b")
                        .gauge())
                .isNull();
        assertThat(node0.get("artemis.registry.self.service.protection.count")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void should_exit_protection_and_expire_stale_leases_when_reset_interval_elapsed() throws Exception {
        Cluster cluster = startCluster("2", "5000");
        ServiceKey a = serviceKey("svc-a");

        heartbeat(cluster, 0, a, List.of("a1"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean sawProtecting = false;
        while (System.nanoTime() < deadline) {
            heartbeat(cluster, 0, serviceKey(KEEP_ALIVE), keepAliveInstances());
            GetSelfProtectionStatusResponse status = protectionStatus(cluster, 0);
            boolean protecting = status.getData().getServicesList().stream()
                    .anyMatch(item -> item.getService().getService().equals("svc-a") && item.getProtecting());
            sawProtecting |= protecting;
            if (sawProtecting && !protecting && instances(cluster, 0, a).isEmpty()) {
                break;
            }
            Thread.sleep(300);
        }

        assertThat(sawProtecting).as("A should enter protection before timeout").isTrue();
        assertThat(protectionStatus(cluster, 0).getData().getServicesList())
                .noneMatch(item -> item.getService().getService().equals("svc-a"));
        assertThat(instances(cluster, 0, a))
                .as("stale lease expires after protection timeout")
                .isEmpty();
    }

    // ---- 设施 ----

    private Cluster startCluster(String windowSeconds, String maxCountResetMs) {
        List<Integer> ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        String members = membersString(ports);
        List<String> extraArgs = List.of(
                "--artemis.registry.lease.ttlMs=" + SHORT_TTL_MS,
                "--artemis.registry.selfProtection.windowSeconds=" + windowSeconds,
                "--artemis.registry.selfProtection.minCount=1",
                "--artemis.registry.selfProtection.factor=0.2",
                "--artemis.registry.selfProtection.maxCountResetMs=" + maxCountResetMs);
        for (int i = 0; i < NODE_COUNT; i++) {
            startNode(i, externalPort(ports, i), peerPort(ports, i), members, extraArgs);
        }
        return new Cluster(ports);
    }

    private void startNode(int index, int externalPort, int peerPort, String members, List<String> extraArgs) {
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
        TestAwait.await(
                "node-" + index + " ready",
                () -> context.getBean(org.mydotey.ai.artemis.registry.application.ReadinessGate.class)
                        .isReady());
    }

    private static String nodeId(int index) {
        return "node-" + index;
    }

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

    private void heartbeat(Cluster cluster, int node, ServiceKey key, List<String> instanceIds)
            throws InterruptedException {
        Heartbeats.collect(cluster.externalChannel(node), heartbeatRequest(key, instanceIds));
    }

    private static HeartbeatRequest heartbeatRequest(ServiceKey key, List<String> instanceIds) {
        ServiceInstances.Builder services = ServiceInstances.newBuilder().setKey(key);
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080)
                    .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                    .setWeight(100));
        }
        return HeartbeatRequest.newBuilder().addServices(services).setSeq(1).build();
    }

    private static List<String> keepAliveInstances() {
        return IntStream.range(0, KEEP_ALIVE_INSTANCES)
                .mapToObj(i -> "keep-" + i)
                .toList();
    }

    private boolean protectingOnly(Cluster cluster, int node, String service) {
        var services = protectionStatus(cluster, node).getData().getServicesList();
        boolean target = services.stream()
                .anyMatch(item -> item.getService().getService().equals(service) && item.getProtecting());
        // keep-alive 是测试设施（维持节点级健康），其自身保护态不影响「只保护目标服务」断言
        boolean other = services.stream()
                .anyMatch(item -> !item.getService().getService().equals(service)
                        && !item.getService().getService().equals(KEEP_ALIVE)
                        && item.getProtecting());
        return target && !other;
    }

    private GetSelfProtectionStatusResponse protectionStatus(Cluster cluster, int node) {
        return cluster.queryProtection(node);
    }

    private List<Instance> instances(Cluster cluster, int node, ServiceKey key) {
        return cluster.queryInstances(node, key);
    }

    private static ServiceKey serviceKey(String service) {
        return ServiceKey.newBuilder()
                .setNamespace(NAMESPACE)
                .setService(service)
                .build();
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

    /** 集群句柄：外部端口驱动心跳、内部端口只读查询。 */
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

        private ManagedChannel peerChannel(int index) {
            return peerChannels.computeIfAbsent(
                    index,
                    ignored -> ManagedChannelBuilder.forAddress("127.0.0.1", peerPort(ports, index))
                            .usePlaintext()
                            .build());
        }

        private GetSelfProtectionStatusResponse queryProtection(int index) {
            return RegistryQueryServiceGrpc.newBlockingStub(peerChannel(index))
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getSelfProtectionStatus(GetSelfProtectionStatusRequest.newBuilder()
                            .setNamespace(NAMESPACE)
                            .build());
        }

        private List<Instance> queryInstances(int index, ServiceKey key) {
            return RegistryQueryServiceGrpc.newBlockingStub(peerChannel(index))
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getServiceInstances(
                            GetServiceInstancesRequest.newBuilder().setKey(key).build())
                    .getData()
                    .getInstancesList();
        }
    }
}
