package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 进程内 3 节点集群：各自内部端口 + 真实 gRPC，验证静态成员互见、自身识别与跨节点可达。
 * 内部认证默认关闭（本用例不配凭证）。
 */
class ClusterMembershipIT {

    private static final int NODE_COUNT = 3;

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @Test
    void should_see_all_members_and_reach_each_peer_when_three_nodes_started() throws Exception {
        List<Integer> peerPorts = reservePorts(NODE_COUNT);
        String members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPorts.get(i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(startNode(i, peerPorts.get(i), members));
        }

        for (int i = 0; i < NODE_COUNT; i++) {
            ManagedChannel channel = channel(peerPorts.get(i));
            channels.add(channel);
            ReplicationServiceGrpc.ReplicationServiceBlockingStub stub =
                    ReplicationServiceGrpc.newBlockingStub(channel);

            GetNodeStatusResponse response = stub.getNodeStatus(GetNodeStatusRequest.getDefaultInstance());
            assertThat(response.getData().getSelf().getNodeId()).isEqualTo(nodeId(i));
            assertThat(response.getData().getSelf().getState()).isEqualTo(NodeState.NODE_STATE_UP);
            assertThat(response.getData().getMembersList())
                    .extracting(member -> member.getNodeId())
                    .containsExactlyElementsOf(IntStream.range(0, NODE_COUNT)
                            .mapToObj(this::nodeId)
                            .toList());
            // 成员表 peer 端点必须与配置一致，且每个端点都能应答（节点间实际复制调用随 phase-2）
            assertThat(response.getData().getMembersList())
                    .extracting(member -> member.getPeerHost() + ":" + member.getPeerPort())
                    .containsExactlyElementsOf(
                            peerPorts.stream().map(port -> "127.0.0.1:" + port).toList());
            for (var member : response.getData().getMembersList()) {
                ManagedChannel memberChannel = channel(member.getPeerHost(), member.getPeerPort());
                try {
                    assertThat(ReplicationServiceGrpc.newBlockingStub(memberChannel)
                                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                                    .getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                                    .getData()
                                    .getSelf()
                                    .getNodeId())
                            .isEqualTo(member.getNodeId());
                } finally {
                    memberChannel.shutdownNow();
                }
            }
        }
    }

    private ConfigurableApplicationContext startNode(int index, int peerPort, String members) {
        return new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.port=0",
                        "--artemis.registry.internal.port=" + peerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.cluster.members=" + members,
                        "--artemis.registry.cluster.self=" + nodeId(index),
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + index + ".bin"));
    }

    private String nodeId(int index) {
        return "node-" + index;
    }

    private static ManagedChannel channel(int port) {
        return channel("localhost", port);
    }

    private static ManagedChannel channel(String host, int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    private static List<Integer> reservePorts(int count) {
        return TestGrpcPorts.reservePorts(count);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : channels) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        for (ConfigurableApplicationContext context : contexts) {
            context.close();
        }
    }
}
