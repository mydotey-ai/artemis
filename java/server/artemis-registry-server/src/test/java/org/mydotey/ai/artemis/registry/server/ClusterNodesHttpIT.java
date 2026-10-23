package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 节点列表发现端点（SDK 前置寻址消费）：GET /cluster/nodes 认证生效（无凭证 401）、
 * 带 Basic 认证返回可解析 GetNodeStatusResponse protobuf bytes（self + 全成员 external 端点与观测态）。
 */
class ClusterNodesHttpIT {

    private static final int NODE_COUNT = 3;

    @TempDir
    Path tempDir;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private List<Integer> ports;

    @Test
    void should_serveNodeListViaHttp_when_clusterNodesRequested() throws Exception {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 3);
        startCluster();
        awaitAllReady();

        HttpURLConnection unauthenticated = get(0, null);
        assertThat(unauthenticated.getResponseCode()).isEqualTo(401);
        unauthenticated.disconnect();

        HttpURLConnection authenticated = get(0, "artemis:artemis");
        assertThat(authenticated.getResponseCode()).isEqualTo(200);
        GetNodeStatusResponse response = GetNodeStatusResponse.parseFrom(body(authenticated));
        authenticated.disconnect();

        assertThat(response.getData().getSelf().getNodeId()).isEqualTo("node-0");
        assertThat(response.getData().getSelf().getState()).isEqualTo(NodeState.NODE_STATE_UP);
        assertThat(response.getData().getMembersList())
                .extracting(member -> member.getNodeId())
                .containsExactlyElementsOf(IntStream.range(0, NODE_COUNT)
                        .mapToObj(i -> "node-" + i)
                        .toList());
        assertThat(response.getData().getMembersList())
                .extracting(member -> member.getExternalHost() + ":" + member.getExternalPort())
                .containsExactlyElementsOf(IntStream.range(0, NODE_COUNT)
                        .mapToObj(i -> "127.0.0.1:" + grpcPort(i))
                        .toList());
    }

    // ---- 设施 ----

    private void startCluster() {
        String members = IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(i) + "@127.0.0.1:" + grpcPort(i))
                .collect(Collectors.joining(","));
        for (int i = 0; i < NODE_COUNT; i++) {
            contexts.add(new SpringApplicationBuilder(ArtemisRegistryServer.class)
                    .run(
                            "--spring.application.name=artemis-registry",
                            "--spring.grpc.server.port=" + grpcPort(i),
                            "--server.port=" + httpPort(i),
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

    private HttpURLConnection get(int index, String userInfo) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:" + httpPort(index) + "/cluster/nodes")
                        .toURL()
                        .openConnection();
        if (userInfo != null) {
            connection.setRequestProperty(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(userInfo.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return connection;
    }

    private static byte[] body(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getInputStream();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static String nodeId(int index) {
        return "node-" + index;
    }

    private int peerPort(int index) {
        return ports.get(index * 3 + 1);
    }

    private int grpcPort(int index) {
        return ports.get(index * 3);
    }

    private int httpPort(int index) {
        return ports.get(index * 3 + 2);
    }

    @AfterEach
    void tearDown() {
        for (ConfigurableApplicationContext context : contexts) {
            if (context != null) {
                context.close();
            }
        }
        contexts.clear();
    }
}
