package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
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
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistrySnapshotCodec;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * peer 全量 bootstrap 端到端：新节点加入同步、重启优先 peer、无 UP peer 回退本地快照、
 * 已就绪 peer 同步失败保持 not ready 并退避重试恢复；not ready 期间节点状态 API 可见 STARTING
 * 且快照不落盘。
 */
class PeerBootstrapIT {

    private static final int NODE_COUNT = 3;
    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    @TempDir
    Path tempDir;

    private final Map<Integer, ConfigurableApplicationContext> contexts = new HashMap<>();
    private final Map<Integer, ManagedChannel> externalChannels = new HashMap<>();
    private final Map<Integer, ManagedChannel> statusChannels = new HashMap<>();
    private List<Integer> ports;

    @Test
    void should_sync_full_registry_from_ready_peer_when_new_node_joins() {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        String members = membersString();
        startNode(0, members);
        startNode(1, members);
        awaitReady(0);
        awaitReady(1);

        register(0, "i1");
        awaitInstance(1, "i1");

        startNode(2, members); // 新节点从任一 UP peer 全量同步
        awaitReady(2);
        awaitInstance(2, "i1");
    }

    @Test
    void should_prefer_peer_sync_over_stale_snapshot_when_node_restarts() {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        String members = membersString();
        for (int i = 0; i < NODE_COUNT; i++) {
            startNode(i, members);
        }
        awaitReady();

        register(2, "i1");
        awaitInstance(0, "i1");
        writeSnapshot(2); // 本地快照含 i1
        closeNode(2);

        register(0, "i2"); // 停机期间 peer 数据变化
        awaitInstance(1, "i2");

        startNode(2, members); // 重启优先 peer 同步（本地旧快照不回放）
        awaitReady(2);
        awaitInstances(2, "i1", "i2");
    }

    @Test
    void should_fallback_to_local_snapshot_when_peers_unreachable() throws IOException {
        ports = TestGrpcPorts.reservePorts(NODE_COUNT * 2);
        Path snapshot = tempDir.resolve("snapshot-2.bin");
        byte[] data = new RegistrySnapshotCodec()
                .encode(List.of(new ServiceInstances(KEY, List.of(instance("i-local")))), System.currentTimeMillis());
        Files.write(snapshot, data);

        startNode(2, membersString()); // node-0/node-1 未启动

        awaitReady(2);
        awaitInstances(2, "i-local");
    }

    @Test
    void should_stay_not_ready_and_recover_when_bootstrap_fails_then_peer_recovers() throws Exception {
        int fakePeerPort = TestGrpcPorts.freePort();
        int selfExternalPort = TestGrpcPorts.freePort();
        int selfPeerPort = TestGrpcPorts.freePort();
        FakePeer fake = new FakePeer(fakePeerPort);
        try {
            // fake peer 的对外端点仅配置占位（本用例不从 SDK 列表消费它）
            String members = "node-a@127.0.0.1:" + selfPeerPort + "@127.0.0.1:" + selfExternalPort
                    + ",node-fake@127.0.0.1:" + fakePeerPort + "@127.0.0.1:1";
            Path snapshot = tempDir.resolve("not-ready.bin");
            startCustomNode(
                    0,
                    selfExternalPort,
                    selfPeerPort,
                    members,
                    snapshot,
                    List.of("--artemis.registry.snapshot.intervalMs=1000"));

            TestAwait.await("first bootstrap attempt", () -> metric(0, "artemis.registry.bootstrap.attempts") >= 1);
            assertThat(readiness(0).isReady()).isFalse();

            // read readiness 门控豁免：STARTING 可观测；fake peer 探测为 UP
            TestAwait.await(
                    "self STARTING via node status api",
                    () -> nodeStatus(selfExternalPort).getData().getSelf().getState() == NodeState.NODE_STATE_STARTING);
            TestAwait.await(
                    "fake peer probed UP",
                    () -> memberState(nodeStatus(selfExternalPort), "node-fake") == NodeState.NODE_STATE_UP);

            Thread.sleep(1500); // 跨过一个快照周期：not ready 不落盘
            assertThat(Files.exists(snapshot)).isFalse();

            fake.failBootstrap = false;
            TestAwait.await("recovered and ready", () -> readiness(0).isReady());
            awaitInstance(0, "i1");
        } finally {
            fake.close();
        }
    }

    // ---- 设施 ----

    private void startNode(int index, String members) {
        List<String> args = bootstrapArgs(index, members);
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ArtemisRegistryServer.class).run(args.toArray(String[]::new));
        contexts.put(index, context);
    }

    private void startCustomNode(
            int index, int externalPort, int peerPort, String members, Path snapshot, List<String> extra) {
        List<String> args = new ArrayList<>(bootstrapArgs());
        args.add("--spring.grpc.server.port=" + externalPort);
        args.add("--artemis.registry.internal.port=" + peerPort);
        args.add("--artemis.registry.internal.address=127.0.0.1");
        args.add("--artemis.registry.cluster.members=" + members);
        args.add("--artemis.registry.cluster.self=node-a");
        args.add("--artemis.registry.snapshot.path=" + snapshot);
        args.addAll(extra);
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ArtemisRegistryServer.class).run(args.toArray(String[]::new));
        contexts.put(index, context);
    }

    private List<String> bootstrapArgs(int index, String members) {
        List<String> args = new ArrayList<>(bootstrapArgs());
        args.add("--spring.grpc.server.port=" + externalPort(index));
        args.add("--artemis.registry.internal.port=" + peerPort(index));
        args.add("--artemis.registry.internal.address=127.0.0.1");
        args.add("--artemis.registry.cluster.members=" + members);
        args.add("--artemis.registry.cluster.self=" + nodeId(index));
        args.add("--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + index + ".bin"));
        return args;
    }

    private static List<String> bootstrapArgs() {
        return new ArrayList<>(List.of(
                "--spring.application.name=artemis-registry",
                "--spring.main.web-application-type=none",
                "--management.server.port=0",
                "--artemis.registry.lease.ttlMs=600000",
                "--artemis.registry.bootstrap.retryInitialMs=100",
                "--artemis.registry.bootstrap.retryMaxMs=100",
                "--artemis.registry.cluster.probeIntervalMs=200",
                "--artemis.registry.cluster.probeTimeoutMs=1000"));
    }

    private String membersString() {
        return IntStream.range(0, NODE_COUNT)
                .mapToObj(i -> nodeId(i) + "@127.0.0.1:" + peerPort(i) + "@127.0.0.1:" + externalPort(i))
                .collect(Collectors.joining(","));
    }

    private void awaitReady() {
        for (int i = 0; i < NODE_COUNT; i++) {
            awaitReady(i);
        }
    }

    private void awaitReady(int index) {
        TestAwait.await("node-" + index + " ready", () -> readiness(index).isReady());
    }

    private ReadinessGate readiness(int index) {
        return contexts.get(index).getBean(ReadinessGate.class);
    }

    private double metric(int index, String name) {
        return contexts.get(index)
                .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .get(name)
                .counter()
                .count();
    }

    private RegistryService registry(int index) {
        return contexts.get(index).getBean(RegistryService.class);
    }

    private void register(int index, String instanceId) {
        assertThat(RegistryServiceGrpc.newBlockingStub(externalChannel(index))
                        .withInterceptors(AuthClients.authenticated())
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .register(RegisterRequest.newBuilder()
                                .addServices(protoServiceInstances(instanceId))
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private void awaitInstance(int index, String instanceId) {
        TestAwait.await(
                "node-" + index + " sees " + instanceId,
                () -> instanceIds(index).contains(instanceId));
    }

    private void awaitInstances(int index, String... expected) {
        TestAwait.await(
                "node-" + index + " sees " + List.of(expected),
                () -> instanceIds(index).containsAll(List.of(expected)));
    }

    private List<String> instanceIds(int index) {
        return registry(index)
                .getInstances(KEY)
                .map(s -> s.instances().stream().map(Instance::instanceId).toList())
                .orElse(List.of());
    }

    private void writeSnapshot(int index) {
        registry(index).writeSnapshot();
    }

    private void closeNode(int index) {
        ConfigurableApplicationContext context = contexts.remove(index);
        if (context != null) {
            context.close();
        }
        ManagedChannel channel = externalChannels.remove(index);
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private ManagedChannel externalChannel(int index) {
        return externalChannels.computeIfAbsent(
                index,
                ignored -> ManagedChannelBuilder.forAddress("127.0.0.1", externalPort(index))
                        .usePlaintext()
                        .build());
    }

    private GetNodeStatusResponse nodeStatus(int externalPort) {
        ManagedChannel channel = statusChannels.computeIfAbsent(
                externalPort,
                port -> ManagedChannelBuilder.forAddress("127.0.0.1", port)
                        .usePlaintext()
                        .build());
        return org.mydotey.ai.artemis.proto.replication.v1.NodeStatusServiceGrpc.newBlockingStub(channel)
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

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceInstances protoServiceInstances(String instanceId) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                        .setNamespace(KEY.namespace())
                        .setService(KEY.service()))
                .addInstances(org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                        .setInstanceId(instanceId)
                        .setHost("10.0.0.1")
                        .setPort(8080)
                        .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP)
                        .setWeight(100))
                .build();
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static String nodeId(int index) {
        return "node-" + index;
    }

    private int peerPort(int index) {
        return ports.get(index * 2 + 1);
    }

    private int externalPort(int index) {
        return ports.get(index * 2);
    }

    /** 自报 UP、可切换 bootstrap 失败的假 peer（验证 not ready 重试与恢复）。 */
    private static final class FakePeer extends ReplicationServiceGrpc.ReplicationServiceImplBase
            implements AutoCloseable {

        private final Server server;
        private volatile boolean failBootstrap = true;

        private FakePeer(int port) throws IOException {
            this.server = ServerBuilder.forPort(port).addService(this).build().start();
        }

        @Override
        public void getNodeStatus(
                GetNodeStatusRequest request, StreamObserver<GetNodeStatusResponse> responseObserver) {
            responseObserver.onNext(GetNodeStatusResponse.newBuilder()
                    .setStatus(successStatus())
                    .setData(GetNodeStatusResponseData.newBuilder()
                            .setSelf(org.mydotey.ai.artemis.proto.replication.v1.NodeStatus.newBuilder()
                                    .setNodeId("node-fake")
                                    .setState(NodeState.NODE_STATE_UP)
                                    .setStartedAtMs(0)))
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void bootstrap(BootstrapRequest request, StreamObserver<BootstrapResponse> responseObserver) {
            if (failBootstrap) {
                responseObserver.onError(
                        Status.INTERNAL.withDescription("bootstrap down").asRuntimeException());
                return;
            }
            responseObserver.onNext(BootstrapResponse.newBuilder()
                    .setStatus(successStatus())
                    .setData(BootstrapResponseData.newBuilder().addServices(protoServiceInstances("i1")))
                    .build());
            responseObserver.onCompleted();
        }

        private static ResponseStatus successStatus() {
            return ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build();
        }

        @Override
        public void close() {
            server.shutdownNow();
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : externalChannels.values()) {
            if (channel != null) {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        externalChannels.clear();
        for (ManagedChannel channel : statusChannels.values()) {
            channel.shutdownNow();
        }
        statusChannels.clear();
        for (ConfigurableApplicationContext context : contexts.values()) {
            if (context != null) {
                context.close();
            }
        }
        contexts.clear();
    }
}
