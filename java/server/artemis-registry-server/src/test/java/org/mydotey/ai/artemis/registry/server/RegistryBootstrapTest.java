package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistryServiceImpl;
import org.mydotey.ai.artemis.registry.application.RegistrySnapshotCodec;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerBootstrapSource;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.infrastructure.FileRegistrySnapshotStore;
import org.mydotey.scf.facade.StringProperties;

/**
 * 启动编排：单节点快照分支（缺失/损坏/超期均不阻断）、集群分支①（UP peer 全量同步、
 * 无 UP peer 回退快照、同步失败重试、已确认数据源后不降级快照）。
 */
class RegistryBootstrapTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");
    private static final ClusterMember SELF = member("node-a", 19093);
    private static final ClusterMember PEER_B = member("node-b", 19094);
    private static final ClusterMember PEER_C = member("node-c", 19095);

    @TempDir
    Path tempDir;

    @Test
    void should_be_ready_with_missing_snapshot() {
        Result result = bootstrap(tempDir.resolve("absent.bin"));

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    @Test
    void should_be_ready_with_corrupt_snapshot() throws IOException {
        Path file = tempDir.resolve("snap.bin");
        Files.write(file, new byte[] {1, 2, 3});

        Result result = bootstrap(file);

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    @Test
    void should_be_ready_with_stale_snapshot() throws IOException {
        Path file = tempDir.resolve("snap.bin");
        byte[] stale =
                new RegistrySnapshotCodec().encode(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), 0L);
        Files.write(file, stale);

        Result result = bootstrap(file);

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    @Test
    void should_restore_snapshot_when_no_ready_peer_in_cluster() throws IOException {
        Path file = writeValidSnapshot("i-local");
        Setup setup = setup(file);
        FakePeerSource source = new FakePeerSource();
        source.statusUp = false; // STARTING

        RegistryBootstrap bootstrap = bootstrap(setup, members(SELF, PEER_B), source);

        assertThat(bootstrap).isNotNull();
        assertThat(setup.gate().isReady()).isTrue();
        assertThat(instanceIds(setup.service())).containsExactly("i-local");
    }

    @Test
    void should_apply_peer_snapshot_and_skip_local_snapshot_when_ready_peer_exists() throws IOException {
        Path file = writeValidSnapshot("i-local");
        Setup setup = setup(file);
        FakePeerSource source = new FakePeerSource();
        source.snapshots.put("node-b", List.of(new ServiceInstances(KEY, List.of(instance("i-peer")))));

        bootstrap(setup, members(SELF, PEER_B), source);

        assertThat(setup.gate().isReady()).isTrue();
        assertThat(instanceIds(setup.service())).containsExactly("i-peer");
    }

    @Test
    void should_try_next_ready_peer_when_first_candidate_sync_fails() throws IOException {
        Path file = writeValidSnapshot("i-local");
        Setup setup = setup(file);
        FakePeerSource source = new FakePeerSource();
        source.failBootstrap.add("node-b");
        source.snapshots.put("node-c", List.of(new ServiceInstances(KEY, List.of(instance("i-peer")))));

        bootstrap(setup, members(SELF, PEER_B, PEER_C), source);

        assertThat(setup.gate().isReady()).isTrue();
        assertThat(instanceIds(setup.service())).containsExactly("i-peer");
    }

    @Test
    void should_stay_not_ready_and_retry_when_peer_sync_fails_then_recover() throws IOException {
        Path file = writeValidSnapshot("i-local");
        Setup setup = setup(file);
        FakePeerSource source = new FakePeerSource();
        source.failBootstrap.add("node-b");

        RegistryBootstrap bootstrap = bootstrap(setup, members(SELF, PEER_B), source);

        assertThat(setup.gate().isReady()).isFalse(); // 不降级本地快照
        TestAwait.await(
                "bootstrap retried",
                () -> setup.meters()
                                .get("artemis.registry.bootstrap.attempts")
                                .counter()
                                .count()
                        >= 2);

        source.failBootstrap.clear();
        source.snapshots.put("node-b", List.of(new ServiceInstances(KEY, List.of(instance("i-peer")))));
        TestAwait.await("recovered and ready", setup.gate()::isReady);
        assertThat(instanceIds(setup.service())).containsExactly("i-peer");
        bootstrap.close();
    }

    @Test
    void should_not_fallback_to_snapshot_when_peer_was_seen_but_now_unreachable() throws IOException {
        Path file = writeValidSnapshot("i-local");
        Setup setup = setup(file);
        FakePeerSource source = new FakePeerSource();
        source.failBootstrap.add("node-b");

        RegistryBootstrap bootstrap = bootstrap(setup, members(SELF, PEER_B), source);
        TestAwait.await(
                "bootstrap retried",
                () -> setup.meters()
                                .get("artemis.registry.bootstrap.attempts")
                                .counter()
                                .count()
                        >= 2);

        source.unreachable = true; // 已确认过数据源存在：后续 peer 全部不可达也不回退快照
        TestAwait.await(
                "retry after unreachable",
                () -> setup.meters()
                                .get("artemis.registry.bootstrap.attempts")
                                .counter()
                                .count()
                        >= 3);

        assertThat(setup.gate().isReady()).isFalse();
        assertThat(setup.service().getInstances(KEY)).isEmpty();
        bootstrap.close();
    }

    // ---- 设施 ----

    private Result bootstrap(Path snapshotFile) {
        Setup setup = setup(snapshotFile);
        new RegistryBootstrap(
                        setup.service(),
                        setup.gate(),
                        emptyMembers(),
                        unreachablePeerSource(),
                        setup.properties(),
                        setup.meters())
                .afterPropertiesSet();
        return new Result(setup.service(), setup.gate());
    }

    private RegistryBootstrap bootstrap(Setup setup, ClusterMembers members, PeerBootstrapSource source) {
        RegistryBootstrap bootstrap = new RegistryBootstrap(
                setup.service(), setup.gate(), members, source, setup.properties(), setup.meters());
        bootstrap.attempt();
        return bootstrap;
    }

    private Setup setup(Path snapshotFile) {
        Map<String, String> values = new HashMap<>();
        values.put("artemis.registry.snapshot.path", snapshotFile.toString());
        values.put("artemis.registry.bootstrap.retryInitialMs", "100");
        values.put("artemis.registry.bootstrap.retryMaxMs", "100");
        values.put("artemis.registry.bootstrap.statusTimeoutMs", "100");
        StringProperties properties = TestProperties.of(values).properties();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MonotonicClock monotonicClock = () -> System.nanoTime() / 1_000_000;
        RegistrySnapshotStore store = new FileRegistrySnapshotStore(properties);
        RegistryService service = new RegistryServiceImpl(
                new InstanceRegistry(
                        TestProperties.of(Map.of(InstanceRegistry.KEY_LEASE_TTL_MS, "30000"))
                                .properties(),
                        monotonicClock,
                        change -> {}),
                properties,
                Clock.systemUTC(),
                new SelfProtection(
                        TestProperties.of(Map.of(
                                        SelfProtection.KEY_WINDOW_SECONDS,
                                        "10",
                                        SelfProtection.KEY_MIN_COUNT,
                                        "50",
                                        SelfProtection.KEY_FACTOR,
                                        "0.85"))
                                .properties(),
                        meters,
                        monotonicClock),
                store,
                meters);
        return new Setup(service, new ReadinessGate(), properties, meters);
    }

    private Path writeValidSnapshot(String instanceId) throws IOException {
        Path file = tempDir.resolve("snapshot-" + instanceId + ".bin");
        byte[] snapshot = new RegistrySnapshotCodec()
                .encode(List.of(new ServiceInstances(KEY, List.of(instance(instanceId)))), System.currentTimeMillis());
        Files.write(file, snapshot);
        return file;
    }

    private static List<String> instanceIds(RegistryService service) {
        return service.getInstances(KEY)
                .map(s -> s.instances().stream().map(Instance::instanceId).toList())
                .orElse(List.of());
    }

    private static ClusterMember member(String nodeId, int peerPort) {
        return new ClusterMember(
                nodeId, new ClusterEndpoint("127.0.0.1", peerPort), new ClusterEndpoint("127.0.0.1", peerPort - 3));
    }

    private static ClusterMembers members(ClusterMember self, ClusterMember... peers) {
        List<ClusterMember> all = new ArrayList<>();
        all.add(self);
        all.addAll(List.of(peers));
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return all;
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(self);
            }
        };
    }

    private static ClusterMembers emptyMembers() {
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of();
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.empty();
            }
        };
    }

    private static PeerBootstrapSource unreachablePeerSource() {
        return new PeerBootstrapSource() {
            @Override
            public NodeStatus status(ClusterMember peer, long timeoutMs) {
                throw new UnsupportedOperationException("single-node bootstrap must not probe peers");
            }

            @Override
            public List<ServiceInstances> bootstrap(ClusterMember peer) {
                throw new UnsupportedOperationException("single-node bootstrap must not probe peers");
            }
        };
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, java.util.Map.of());
    }

    private static final class FakePeerSource implements PeerBootstrapSource {

        private volatile boolean statusUp = true;
        private volatile boolean unreachable;
        private final List<String> failBootstrap = new ArrayList<>();
        private final Map<String, List<ServiceInstances>> snapshots = new HashMap<>();

        @Override
        public NodeStatus status(ClusterMember peer, long timeoutMs) {
            if (unreachable) {
                throw new IllegalStateException("unreachable");
            }
            return new NodeStatus(peer.nodeId(), statusUp ? NodeState.UP : NodeState.STARTING, 0);
        }

        @Override
        public List<ServiceInstances> bootstrap(ClusterMember peer) {
            if (failBootstrap.contains(peer.nodeId())) {
                throw new IllegalStateException("bootstrap failed");
            }
            return snapshots.getOrDefault(peer.nodeId(), List.of());
        }
    }

    private record Setup(
            RegistryService service, ReadinessGate gate, StringProperties properties, SimpleMeterRegistry meters) {}

    private record Result(RegistryService service, ReadinessGate gate) {}
}
