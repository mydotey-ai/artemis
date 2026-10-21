package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.proto.replication.v1.NodeStatusServiceGrpc;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 对外节点状态 API（外部端口、认证生效、readiness 豁免）：3 节点启动后成员全部 UP；
 * 停一个节点后，其余节点在探测周期内把该成员标记 UNKNOWN。
 */
class NodeStatusApiIT {

    private static final int NODE_COUNT = 3;

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final Map<Integer, ManagedChannel> externalChannels = new HashMap<>();
    private List<Integer> ports;

    @Test
    void should_expose_self_up_and_member_states_via_node_status_api() {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        startCluster();
        awaitAllReady();
        TestAwait.await(
                "all members probed UP",
                () -> nodeStatus(0).getData().getMembersList().stream()
                        .allMatch(member -> member.getState() == NodeState.NODE_STATE_UP));

        for (int i = 0; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "node-" + node + " members probed UP",
                    () -> nodeStatus(node).getData().getMembersList().stream()
                            .allMatch(member -> member.getState() == NodeState.NODE_STATE_UP));
            GetNodeStatusResponse response = nodeStatus(i);
            assertThat(response.getData().getSelf().getNodeId()).isEqualTo(nodeId(i));
            assertThat(response.getData().getSelf().getState()).isEqualTo(NodeState.NODE_STATE_UP);
            assertThat(response.getData().getMembersList())
                    .extracting(member -> member.getNodeId())
                    .containsExactlyElementsOf(IntStream.range(0, NODE_COUNT)
                            .mapToObj(this::nodeId)
                            .toList());
            assertThat(response.getData().getMembersList())
                    .extracting(member -> member.getExternalHost() + ":" + member.getExternalPort())
                    .containsExactlyElementsOf(IntStream.range(0, NODE_COUNT)
                            .mapToObj(j -> "127.0.0.1:" + externalPort(j))
                            .toList());
            assertThat(response.getData().getMembersList())
                    .extracting(member -> member.getState())
                    .containsOnly(NodeState.NODE_STATE_UP);
        }
    }

    @Test
    void should_mark_stopped_peer_unknown_when_probe_round_runs() throws Exception {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        startCluster();
        awaitAllReady();

        // 关闭 node-2（内部与外部端口随 context 释放）
        ConfigurableApplicationContext node2 = contexts.get(2);
        contexts.set(2, null);
        node2.close();
        ManagedChannel channel2 = externalChannels.remove(2);
        if (channel2 != null) {
            channel2.shutdownNow();
        }

        TestAwait.await(
                "node-2 probed UNKNOWN", () -> memberState(nodeStatus(0), nodeId(2)) == NodeState.NODE_STATE_UNKNOWN);
        assertThat(nodeStatus(0).getData().getSelf().getState()).isEqualTo(NodeState.NODE_STATE_UP);
    }

    // ---- 设施 ----

    private void startCluster() {
        String members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(i) + "@127.0.0.1:" + externalPort(i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(new SpringApplicationBuilder(ArtemisRegistryServer.class)
                    .run(
                            "--spring.application.name=artemis-registry",
                            "--spring.main.web-application-type=none",
                            "--spring.grpc.server.port=" + externalPort(i),
                            "--management.server.port=0",
                            "--artemis.registry.internal.port=" + peerPort(i),
                            "--artemis.registry.internal.address=127.0.0.1",
                            "--artemis.registry.cluster.members=" + members,
                            "--artemis.registry.cluster.self=" + nodeId(i),
                            "--artemis.registry.cluster.probeIntervalMs=200",
                            "--artemis.registry.cluster.probeTimeoutMs=1000",
                            "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + i + ".bin")));
        }
    }

    private void awaitAllReady() {
        for (int i = 0; i < NODE_COUNT; i++) {
            int node = i;
            TestAwait.await(
                    "node-" + node + " ready",
                    () -> contexts.get(node).getBean(ReadinessGate.class).isReady());
        }
    }

    private GetNodeStatusResponse nodeStatus(int index) {
        ManagedChannel channel = externalChannels.computeIfAbsent(
                index,
                ignored -> ManagedChannelBuilder.forAddress("127.0.0.1", externalPort(index))
                        .usePlaintext()
                        .build());
        return NodeStatusServiceGrpc.newBlockingStub(channel)
                .withInterceptors(AuthClients.authenticated())
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getNodeStatus(GetNodeStatusRequest.getDefaultInstance());
    }

    private static NodeState memberState(GetNodeStatusResponse response, String nodeId) {
        return response.getData().getMembersList().stream()
                .filter(member -> member.getNodeId().equals(nodeId))
                .map(member -> member.getState())
                .findFirst()
                .orElse(NodeState.NODE_STATE_UNKNOWN);
    }

    private String nodeId(int index) {
        return "node-" + index;
    }

    private int peerPort(int index) {
        return ports.get(index * 2 + 1);
    }

    private int externalPort(int index) {
        return ports.get(index * 2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : externalChannels.values()) {
            if (channel != null) {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        externalChannels.clear();
        for (ConfigurableApplicationContext context : contexts) {
            if (context != null) {
                context.close();
            }
        }
        contexts.clear();
    }
}
