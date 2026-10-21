package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerBootstrapSource;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;

/** 后台探测：并发、超时、nodeId 校验、每轮重建与恢复。 */
class ClusterNodeStatusMonitorTest {

    private static final ClusterMember SELF = member("node-a", 19093, 19090);
    private static final ClusterMember PEER_B = member("node-b", 19094, 19091);
    private static final ClusterMember PEER_C = member("node-c", 19095, 19092);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ClusterNodeStatusMonitor monitor;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.close();
        }
    }

    @Test
    void should_probe_peers_concurrently_and_skip_self() {
        AtomicInteger probed = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2); // 两个 peer 必须同时进入 ⇒ 并发
        monitor = start(
                peer -> {
                    probed.incrementAndGet();
                    awaitBarrier(barrier);
                    return new NodeStatus(peer.nodeId(), NodeState.UP, 0);
                },
                "10000",
                "3000");

        TestAwait.await(
                "both peers UP",
                () -> monitor.stateOf("node-b") == NodeState.UP && monitor.stateOf("node-c") == NodeState.UP);
        assertThat(probed).hasValue(2); // self 不探测
    }

    @Test
    void should_mark_peer_unknown_when_probe_times_out() {
        AtomicBoolean block = new AtomicBoolean();
        monitor = start(
                peer -> {
                    if (block.get()) {
                        sleepMillis(5000); // 超过 probeTimeout，应被整轮取消
                    }
                    return new NodeStatus(peer.nodeId(), NodeState.UP, 0);
                },
                "100",
                "100");

        TestAwait.await("peer UP", () -> monitor.stateOf("node-b") == NodeState.UP);
        block.set(true);

        TestAwait.await("peer UNKNOWN after timeout", () -> monitor.stateOf("node-b") == NodeState.UNKNOWN);
    }

    @Test
    void should_mark_peer_unknown_when_probe_returns_mismatched_node_id() {
        monitor = start(peer -> new NodeStatus("some-other-node", NodeState.UP, 0), "100", "1000");

        TestAwait.await(
                "probe round ran",
                () -> meters.get("artemis.registry.cluster.probe.rounds")
                                .counter()
                                .count()
                        >= 1);
        assertThat(monitor.stateOf("node-b")).isEqualTo(NodeState.UNKNOWN);
        assertThat(monitor.stateOf("node-c")).isEqualTo(NodeState.UNKNOWN);
    }

    @Test
    void should_recover_peer_state_on_next_round_when_probe_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        monitor = start(
                peer -> {
                    if (calls.incrementAndGet() <= 2) { // 首轮两个 peer 均失败
                        throw new IllegalStateException("peer down");
                    }
                    return new NodeStatus(peer.nodeId(), NodeState.UP, 0);
                },
                "100",
                "1000");

        TestAwait.await(
                "peers recover",
                () -> monitor.stateOf("node-b") == NodeState.UP && monitor.stateOf("node-c") == NodeState.UP);
    }

    // ---- 设施 ----

    private ClusterNodeStatusMonitor start(
            Function<ClusterMember, NodeStatus> statusFunction, String intervalMs, String timeoutMs) {
        PeerBootstrapSource source = new PeerBootstrapSource() {
            @Override
            public NodeStatus status(ClusterMember peer, long timeoutMsValue) {
                return statusFunction.apply(peer);
            }

            @Override
            public List<ServiceInstances> bootstrap(ClusterMember peer) {
                throw new UnsupportedOperationException("not used by probe monitor");
            }
        };
        ClusterNodeStatusMonitor monitor = new ClusterNodeStatusMonitor(
                TestProperties.of(Map.of(
                                ClusterNodeStatusMonitor.KEY_PROBE_INTERVAL_MS,
                                intervalMs,
                                ClusterNodeStatusMonitor.KEY_PROBE_TIMEOUT_MS,
                                timeoutMs))
                        .properties(),
                members(),
                source,
                meters);
        monitor.start();
        return monitor;
    }

    private static ClusterMembers members() {
        List<ClusterMember> list = List.of(SELF, PEER_B, PEER_C);
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return list;
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(SELF);
            }
        };
    }

    private static ClusterMember member(String nodeId, int peerPort, int externalPort) {
        return new ClusterMember(
                nodeId, new ClusterEndpoint("127.0.0.1", peerPort), new ClusterEndpoint("127.0.0.1", externalPort));
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("probes are not concurrent", e);
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
