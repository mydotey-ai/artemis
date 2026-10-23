package org.mydotey.ai.artemis.discovery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.TestAwait;
import org.mydotey.ai.artemis.discovery.TestProperties;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceChange;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSession;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSource;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSyncListener;
import org.mydotey.ai.artemis.discovery.domain.ProjectionWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.UpstreamState;

class ProjectionSynchronizerTest {

    private static final String CLUSTER = "test-cluster";
    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();
    private final List<String> events = new CopyOnWriteArrayList<>(); // synced/disconnected 序列
    private final FakeSource source = new FakeSource();
    private ProjectionSynchronizer synchronizer;

    @AfterEach
    void tearDown() {
        if (synchronizer != null) {
            synchronizer.close();
        }
    }

    private void startSynchronizer(Map<String, String> config) {
        synchronizer = new ProjectionSynchronizer(
                new TestProperties(config).properties(), source, registry, CLUSTER, new ProjectionSyncListener() {
                    @Override
                    public void onSynced() {
                        events.add("synced");
                    }

                    @Override
                    public void onDisconnected() {
                        events.add("disconnected");
                    }
                });
        synchronizer.start();
    }

    @Test
    void should_bootstrap_only_after_establishment_confirmation() throws Exception {
        startSynchronizer(Map.of());
        FakeSession session = source.latestSession();
        session.awaitWatched();

        // watch 已建立但未确认：不发 bootstrap
        session.pauseBootstrap();
        assertThat(session.bootstrapCalls.get()).isZero();

        session.establish();
        TestAwait.await("bootstrap after establishment", () -> session.bootstrapCalls.get() >= 1);
    }

    @Test
    void should_apply_buffered_events_after_bootstrap_cover() throws Exception {
        startSynchronizer(Map.of());
        FakeSession session = source.latestSession();
        session.awaitWatched();
        session.pauseBootstrap(); // 快照读取与流结束由测试控制（D-D3 窗口场景）

        session.establish();
        // 覆盖前到达队列的两个事件：产生于「快照读取前」的 A（快照已含，幂等吸收）
        // 与「快照读取后」的 B（快照不含，必须在覆盖后应用——不得被覆盖吞掉）
        session.push(change(new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of()), ChangeType.NEW));
        session.push(change(new Instance("i2", "10.0.0.2", 8080, InstanceStatus.UP, 100, Map.of()), ChangeType.NEW));
        session.bootstrapResult = List.of(service(instance("i1")));
        session.releaseBootstrap();

        TestAwait.await(
                "buffered event applied after cover", () -> instanceIds(ORDER).contains("i2"));
        assertThat(instanceIds(ORDER)).containsExactlyInAnyOrder("i1", "i2");
        assertThat(events).contains("synced");
    }

    @Test
    void should_rebootstrap_and_cover_stale_data_on_disconnect() throws Exception {
        startSynchronizer(Map.of("artemis.discovery.upstream.reconnectBackoffMinMs", "10"));
        FakeSession first = source.latestSession();
        first.awaitWatched();
        first.bootstrapResult = List.of(service(instance("i1"))); // 先备好快照，再确认（防 bootstrap 先读默认空值）
        first.establish();
        first.push(change(instance("i2"), ChangeType.NEW)); // 流增量

        TestAwait.await("first sync applied", () -> instanceIds(ORDER).containsAll(List.of("i1", "i2")));
        int sessionCount = source.sessions.size();
        first.closeStream(); // 断流

        // 第二轮：上游快照已不含 order-service（空表）→ 覆盖清除陈旧数据
        TestAwait.await("reconnected", () -> source.sessions.size() > sessionCount);
        FakeSession second = source.latestSession();
        second.awaitWatched();
        second.bootstrapResult = List.of(); // 上游已清空（空表快照）
        second.establish();
        TestAwait.await(
                "stale covered by re-bootstrap", () -> instanceIds(ORDER).isEmpty());

        assertThat(events).containsSubsequence("synced", "disconnected");
        assertThat(events.stream().filter("synced"::equals).count())
                .as("onSynced 只在首轮触发（D-D5）")
                .isEqualTo(1);
    }

    @Test
    void should_retry_with_backoff_when_upstream_fails() {
        startSynchronizer(Map.of("artemis.discovery.upstream.reconnectBackoffMinMs", "10"));
        source.failNextOpens.set(2); // 前两次 open 失败（上游不可达）

        TestAwait.await("retried until success", () -> {
            FakeSession session = source.latestSessionOrNull();
            if (session == null) {
                return false;
            }
            session.establish();
            return synchronizer.state() == UpstreamState.SYNCED;
        });
        assertThat(source.openCalls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void should_reconnect_when_event_queue_full() throws Exception {
        // queueCapacity 下限 16：establish 前灌满队列触发慢消费断流
        startSynchronizer(Map.of(
                "artemis.discovery.projection.queueCapacity",
                "16",
                "artemis.discovery.upstream.reconnectBackoffMinMs",
                "10"));
        FakeSession first = source.latestSession();
        first.awaitWatched();
        for (int i = 0; i < 17; i++) {
            first.push(change(instance("overflow-" + i), ChangeType.NEW));
        }
        first.establish();

        TestAwait.await("reconnected after queue full", () -> source.sessions.size() >= 2);
        FakeSession second = source.latestSession();
        second.awaitWatched();
        second.bootstrapResult = List.of(service(instance("i1")));
        second.establish();
        TestAwait.await("second round synced", () -> synchronizer.state() == UpstreamState.SYNCED);
    }

    @Test
    void should_not_open_readiness_until_first_sync() throws Exception {
        startSynchronizer(Map.of());
        FakeSession session = source.latestSession();
        session.awaitWatched();
        session.pauseBootstrap();
        session.establish();

        TestAwait.await("bootstrap issued", () -> session.bootstrapCalls.get() >= 1);
        assertThat(events).doesNotContain("synced"); // 覆盖未落地，readiness 未开

        session.releaseBootstrap();
        TestAwait.await("synced after cover", () -> events.contains("synced"));
    }

    // ---- fake 上游（协议无关，只验同步时序语义）----

    private static Instance instance(String id) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static ServiceInstances service(Instance... instances) {
        return new ServiceInstances(ORDER, List.of(instances));
    }

    private static ServiceChange change(Instance instance, ChangeType type) {
        return new ServiceChange(ORDER, List.of(new InstanceChange(instance, type)));
    }

    private List<String> instanceIds(ServiceKey key) {
        return registry.query(new DiscoverySelector(Set.of(key), Map.of())).stream()
                .filter(s -> s.instances().key().equals(key))
                .findFirst()
                .map(ClusterServiceInstances::instances)
                .map(ServiceInstances::instances)
                .orElse(List.of())
                .stream()
                .map(Instance::instanceId)
                .toList();
    }

    private static final class FakeSource implements ProjectionSource {

        final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicInteger failNextOpens = new AtomicInteger();

        @Override
        public ProjectionSession open(String requesterId) {
            openCalls.incrementAndGet();
            if (failNextOpens.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
                throw new IllegalStateException("upstream unreachable");
            }
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }

        FakeSession latestSession() {
            TestAwait.await("session opened", () -> !sessions.isEmpty());
            return sessions.get(sessions.size() - 1);
        }

        FakeSession latestSessionOrNull() {
            return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
    }

    /** 可控会话：bootstrap 可暂停（模拟流结束时刻），事件可注入（模拟到达序）。 */
    private static final class FakeSession implements ProjectionSession {

        final AtomicInteger bootstrapCalls = new AtomicInteger();
        volatile List<ServiceInstances> bootstrapResult = List.of();
        volatile CountDownLatch watched = new CountDownLatch(1);
        private ProjectionListener listener;
        private CountDownLatch bootstrapGate;

        @Override
        public ProjectionWatch watch(String subscriberId, ProjectionListener projectionListener) {
            this.listener = projectionListener;
            watched.countDown();
            return () -> {};
        }

        @Override
        public List<ServiceInstances> bootstrap() {
            bootstrapCalls.incrementAndGet();
            CountDownLatch gate = bootstrapGate;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return bootstrapResult;
        }

        @Override
        public void close() {
            // 无传输资源
        }

        void awaitWatched() throws InterruptedException {
            assertThat(watched.await(10, TimeUnit.SECONDS)).isTrue();
        }

        void establish() {
            listener.onEstablished();
        }

        void push(ServiceChange change) {
            listener.onChanges(List.of(change));
        }

        void closeStream() {
            listener.onClosed();
        }

        void pauseBootstrap() {
            bootstrapGate = new CountDownLatch(1);
        }

        void releaseBootstrap() {
            bootstrapGate.countDown();
        }
    }
}
