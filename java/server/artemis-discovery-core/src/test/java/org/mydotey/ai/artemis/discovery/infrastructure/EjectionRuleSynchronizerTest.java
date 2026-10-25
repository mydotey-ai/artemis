package org.mydotey.ai.artemis.discovery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.discovery.TestProperties;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.EjectionFilter;
import org.mydotey.ai.artemis.discovery.domain.EjectionRule;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleListener;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleSource;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleWatch;
import org.mydotey.ai.artemis.discovery.domain.EjectionTarget;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

class EjectionRuleSynchronizerTest {

    private static final String CLUSTER = "cluster-a";
    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();
    private final EjectionFilter filter = new EjectionFilter();
    private final SubscriptionManager manager = new SubscriptionManager(registry, DiscoveryFilterChain.of(filter));
    private final FakeSource source = new FakeSource();
    private final ManagementStatus status = new ManagementStatus();

    private EjectionRuleSynchronizer startSynchronizer() {
        return startSynchronizer(50, 100);
    }

    private EjectionRuleSynchronizer startSynchronizer(long backoffMinMs, long backoffMaxMs) {
        EjectionRuleSynchronizer synchronizer = new EjectionRuleSynchronizer(
                new TestProperties(Map.of(
                                "artemis.discovery.management.reconnectBackoffMinMs", String.valueOf(backoffMinMs),
                                "artemis.discovery.management.reconnectBackoffMaxMs", String.valueOf(backoffMaxMs)))
                        .properties(),
                source,
                filter,
                registry,
                manager,
                status);
        synchronizer.start();
        return synchronizer;
    }

    @Test
    void should_report_syncing_then_synced_when_snapshot_applied() throws Exception {
        try (EjectionRuleSynchronizer ignored = startSynchronizer(1_000, 2_000)) {
            source.awaitWatch();
            assertThat(status.state()).isEqualTo(ManagementState.SYNCING);

            source.pushSnapshot(List.of());
            await(() -> status.state() == ManagementState.SYNCED);
        }
    }

    @Test
    void should_report_disconnected_when_stream_lost_after_sync() throws Exception {
        try (EjectionRuleSynchronizer ignored = startSynchronizer(2_000, 4_000)) {
            source.awaitWatch();
            source.pushSnapshot(List.of());
            await(() -> status.state() == ManagementState.SYNCED);

            source.closeCurrent();
            await(() -> status.state() == ManagementState.DISCONNECTED);
        }
    }

    @Test
    void should_report_disabled_when_management_not_configured() {
        EjectionRuleSynchronizer synchronizer = new EjectionRuleSynchronizer(
                new TestProperties(Map.of()).properties(), source, filter, registry, manager, status);
        synchronizer.disable();

        assertThat(status.state()).isEqualTo(ManagementState.DISABLED);
        synchronizer.close();
    }

    @Test
    void should_apply_snapshot_and_fanout_view_changes() throws Exception {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"), instance("i2"))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (EjectionRuleSynchronizer synchronizer = startSynchronizer()) {
            source.awaitWatch(); // watch 建立后事件才不丢（线程启动竞态）
            source.pushSnapshot(List.of(rule("i1")));
            await(() -> filter.rules().isEjected(ORDER, "i1", Map.of()));

            // 视图变更直投：i1 退出视图 DELETE（投影实例完整数据）
            await(() -> target.events.size() == 1);
            assertThat(target.events.get(0).changes())
                    .extracting(change -> change.instance().instanceId(), change -> change.type())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("i1", ChangeType.DELETE));

            // 增量：恢复 i1 → NEW；摘除 i2 → DELETE
            source.pushRecovered(EjectionTarget.of(rule("i1")));
            source.pushEjected(rule("i2"));
            await(() -> target.events.size() == 3);
            assertThat(target.events.get(1).changes()).hasSize(1);
            assertThat(target.events.get(2).changes()).hasSize(1);
            assertThat(filter.rules().isEjected(ORDER, "i2", Map.of())).isTrue();
            assertThat(filter.rules().isEjected(ORDER, "i1", Map.of())).isFalse();
        }
    }

    @Test
    void should_fanout_selector_scope_to_all_services() throws Exception {
        // 两个服务各带 metadata，zone 摘除扇出跨服务
        registry.replaceAll(
                CLUSTER,
                List.of(
                        service(ORDER, instance("i1", Map.of("zone", "dc-2")), instance("i2", Map.of("zone", "dc-1"))),
                        service(PAY, instance("p1", Map.of("zone", "dc-2")))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (EjectionRuleSynchronizer synchronizer = startSynchronizer()) {
            source.awaitWatch();
            source.pushEjected(EjectionRule.selector(EjectionScope.ZONE, "dc-2", "zone down"));

            // selector 扇出全投影服务：ORDER/i1 与 PAY/p1 退出视图（dc-1 不命中）
            await(() -> filter.rules().isEjected(ORDER, "i1", Map.of("zone", "dc-2")));
            await(() -> target.events.size() == 2);
            assertThat(target.events).extracting(ServiceChange::key).containsExactlyInAnyOrder(ORDER, PAY);
            assertThat(target.events.stream()
                            .flatMap(change -> change.changes().stream())
                            .map(change -> change.instance().instanceId()))
                    .containsExactlyInAnyOrder("i1", "p1");

            // 恢复 selector：命中实例回归 NEW
            source.pushRecovered(new EjectionTarget(EjectionScope.ZONE, null, null, "dc-2"));
            await(() -> !filter.rules().isEjected(ORDER, "i1", Map.of("zone", "dc-2")));
            await(() -> target.events.size() == 4);
        }
    }

    @Test
    void should_not_fanout_when_rules_unchanged() throws Exception {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (EjectionRuleSynchronizer synchronizer = startSynchronizer()) {
            source.awaitWatch();
            source.pushSnapshot(List.of(rule("i1")));
            await(() -> target.events.size() == 1); // 首次摘除 DELETE

            // 幂等重复推送：无扇出
            source.pushEjected(rule("i1"));
            Thread.sleep(200);
            assertThat(target.events).hasSize(1);
        }
    }

    @Test
    void should_keep_last_known_rules_when_stream_closed() throws Exception {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"))));
        try (EjectionRuleSynchronizer ignored = startSynchronizer()) {
            source.awaitWatch();
            source.pushSnapshot(List.of(rule("i1")));
            await(() -> filter.rules().isEjected(ORDER, "i1", Map.of()));

            source.closeCurrent(); // 断流：缓存不清（D-E3）
            Thread.sleep(200);

            assertThat(filter.rules().isEjected(ORDER, "i1", Map.of())).isTrue();
            // 查询出口继续过滤
            List<org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances> view =
                    registry.query(new DiscoverySelector(Set.of(), Map.of()), DiscoveryFilterChain.of(filter));
            assertThat(view).isEmpty();

            // 重连恢复：重新全量快照（替换语义）收敛
            source.awaitWatch(); // 重连轮次建立新 watch
            source.pushSnapshot(List.of());
            await(() -> !filter.rules().isEjected(ORDER, "i1", Map.of()));
        }
    }

    private static EjectionRule rule(String instanceId) {
        return EjectionRule.instance(ORDER, instanceId, "test");
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
    }

    private static Instance instance(String id) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static Instance instance(String id, Map<String, String> metadata) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, metadata);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within timeout");
            }
            Thread.sleep(10);
        }
    }

    /** 测试源：手动推送事件 + 可控断流。 */
    private static final class FakeSource implements EjectionRuleSource {

        private final List<Session> sessions = new ArrayList<>();

        @Override
        public synchronized EjectionRuleWatch watch(EjectionRuleListener listener) {
            Session session = new Session(listener);
            sessions.add(session);
            return session;
        }

        synchronized void pushSnapshot(List<EjectionRule> rules) {
            sessions.forEach(session -> session.listener.onSnapshot(rules));
        }

        synchronized void pushEjected(EjectionRule rule) {
            sessions.forEach(session -> session.listener.onEjected(rule));
        }

        synchronized void pushRecovered(EjectionTarget target) {
            sessions.forEach(session -> session.listener.onRecovered(target));
        }

        /** 等 watch 建立（重连轮次 = 等新 session，取代旧计数）。 */
        void awaitWatch() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sessionCount() == 0) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("watch not established within timeout");
                }
                Thread.sleep(10);
            }
        }

        private synchronized int sessionCount() {
            return sessions.size();
        }

        synchronized void closeCurrent() {
            sessions.forEach(session -> session.listener.onClosed());
            sessions.clear();
        }
    }

    private record Session(EjectionRuleListener listener) implements EjectionRuleWatch {
        @Override
        public void close() {}
    }

    private static final class RecordingTarget implements PushTarget {

        final List<ServiceChange> events = new ArrayList<>();

        @Override
        public void established() {}

        @Override
        public void accept(String cluster, ServiceChange change) {
            events.add(change);
        }
    }
}
