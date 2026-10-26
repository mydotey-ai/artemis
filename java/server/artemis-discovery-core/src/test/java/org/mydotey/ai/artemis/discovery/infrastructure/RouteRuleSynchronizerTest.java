package org.mydotey.ai.artemis.discovery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.TestProperties;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.GroupFilter;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleKey;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleListener;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleSource;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

/** 路由规则同步引擎（phase-3 接执行点）：应用 + ack 推进、规则变化视图扇出直投、断连保留缓存（fail-open）。 */
class RouteRuleSynchronizerTest {

    private static final String CLUSTER = "cluster-a";
    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();
    private final GroupFilter filter = new GroupFilter();
    private final SubscriptionManager manager = new SubscriptionManager(registry, DiscoveryFilterChain.of(filter));
    private final FakeSource source = new FakeSource();
    private final ManagementStatus status = new ManagementStatus();

    private RouteRuleSynchronizer startSynchronizer(long backoffMinMs, long backoffMaxMs) {
        RouteRuleSynchronizer synchronizer = new RouteRuleSynchronizer(
                new ManagementSyncProperties(new TestProperties(Map.of(
                                "artemis.discovery.management.reconnectBackoffMinMs",
                                String.valueOf(backoffMinMs),
                                "artemis.discovery.management.reconnectBackoffMaxMs",
                                String.valueOf(backoffMaxMs)))
                        .properties()),
                source,
                filter,
                registry,
                manager,
                status);
        synchronizer.start();
        return synchronizer;
    }

    @Test
    void should_apply_and_ack_after_each_event() throws Exception {
        try (RouteRuleSynchronizer synchronizer = startSynchronizer(2_000, 4_000)) {
            source.awaitWatch();

            source.pushSnapshot(List.of(rule("beta")), 5);
            await(() -> synchronizer.rules().size() == 1);
            assertThat(status.state()).isEqualTo(ManagementState.SYNCED);
            assertThat(status.appliedVersion()).isEqualTo(5);
            assertThat(source.ackedVersions).containsExactly(5L);
            assertThat(synchronizer.rules().find(ORDER, "beta")).isPresent();

            source.pushUpserted(rule("v2"), 6);
            await(() -> synchronizer.rules().find(ORDER, "v2").isPresent());
            assertThat(source.ackedVersions).containsExactly(5L, 6L);

            source.pushDeleted(new RouteRuleKey(ORDER, "v2"), 7);
            await(() -> synchronizer.rules().find(ORDER, "v2").isEmpty());
            assertThat(synchronizer.rules().find(ORDER, "beta")).isPresent(); // 快照目标保留
            assertThat(source.ackedVersions).containsExactly(5L, 6L, 7L);
        }
    }

    @Test
    void should_fanout_view_changes_on_rule_change() throws Exception {
        // beta 实例归 beta 组、i2 无分组（归兜底）
        registry.replaceAll(
                CLUSTER, List.of(service(ORDER, instance("i1", Map.of("group", "beta")), instance("i2", Map.of()))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (RouteRuleSynchronizer synchronizer = startSynchronizer(50, 100)) {
            source.awaitWatch();

            // 快照：beta 组 weight 0（摘出）、兜底 70 → i1 退出视图 DELETE
            source.pushSnapshot(List.of(betaRule(0)), 1);
            await(() -> target.events.size() == 1);
            assertThat(target.events.get(0).key()).isEqualTo(ORDER);
            assertThat(target.events.get(0).changes())
                    .extracting(change -> change.instance().instanceId(), change -> change.type())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("i1", ChangeType.DELETE));

            // 增量 upsert：beta 组 release 到 weight 100（跨 0）→ i1 回归 NEW
            source.pushUpserted(betaRule(100), 2);
            await(() -> target.events.size() == 2);
            assertThat(target.events.get(1).changes())
                    .extracting(change -> change.instance().instanceId(), change -> change.type())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("i1", ChangeType.NEW));

            // 增量 delete：规则移除 → 视图回归无治理态（i1 已可见，无新事件）
            source.pushDeleted(new RouteRuleKey(ORDER, "release"), 3);
            await(() -> synchronizer.rules().isEmpty());
            assertThat(target.events).hasSize(2);
        }
    }

    @Test
    void should_limit_fanout_to_rule_scoped_services() throws Exception {
        // 两个服务各有实例，规则只作用 ORDER：PAY 视图不受影响
        registry.replaceAll(
                CLUSTER,
                List.of(
                        service(ORDER, instance("i1", Map.of("group", "beta"))),
                        service(PAY, instance("p1", Map.of("group", "beta")))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (RouteRuleSynchronizer synchronizer = startSynchronizer(50, 100)) {
            source.awaitWatch();
            source.pushSnapshot(List.of(betaRule(0)), 1); // beta 摘出，兜底 70：i1/p1 均归 beta 组

            await(() -> target.events.size() == 1);
            assertThat(target.events.get(0).key()).isEqualTo(ORDER); // PAY 无规则不扇出
            assertThat(filter.isVisible(PAY, instance("p1", Map.of("group", "beta"))))
                    .isTrue(); // 判定亦只作用规则服务
        }
    }

    @Test
    void should_skip_fanout_when_rules_unchanged() throws Exception {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", Map.of("group", "beta")))));
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of()), target);
        target.events.clear();

        try (RouteRuleSynchronizer synchronizer = startSynchronizer(50, 100)) {
            source.awaitWatch();
            source.pushSnapshot(List.of(betaRule(0)), 1);
            await(() -> target.events.size() == 1); // 首次摘出 DELETE

            source.pushUpserted(betaRule(0), 2); // 幂等重推：同内容无扇出
            await(() -> status.appliedVersion() == 2); // 事件已应用（确定性锚点）
            assertThat(target.events).hasSize(1);
        }
    }

    @Test
    void should_keep_last_known_rules_when_disconnected() throws Exception {
        RouteRuleSynchronizer synchronizer = startSynchronizer(60_000, 120_000); // 长退避：断流后不重连
        try {
            source.awaitWatch();
            source.pushSnapshot(List.of(rule("beta")), 3);
            await(() -> synchronizer.rules().size() == 1);

            source.closeCurrent(); // 断流
            await(() -> status.state() == ManagementState.DISCONNECTED);

            // fail-open：断连不清缓存，最后已知规则继续
            assertThat(synchronizer.rules().find(ORDER, "beta")).isPresent();
        } finally {
            synchronizer.close();
        }
    }

    @Test
    void should_tolerate_ack_failure_and_reack_on_next_event() throws Exception {
        try (RouteRuleSynchronizer synchronizer = startSynchronizer(2_000, 4_000)) {
            source.awaitWatch();
            source.failAcks = true;
            source.pushSnapshot(List.of(rule("beta")), 4);

            await(() -> synchronizer.rules().size() == 1); // 应用不受 ack 失败影响
            assertThat(status.appliedVersion()).isEqualTo(4); // 本地版本照常推进

            source.failAcks = false;
            source.pushDeleted(new RouteRuleKey(ORDER, "beta"), 9);
            await(() -> synchronizer.rules().isEmpty());
            assertThat(source.ackedVersions).containsExactly(9L); // 下一事件重新 ack 收敛
        }
    }

    @Test
    void should_report_disabled_when_management_not_configured() {
        RouteRuleSynchronizer synchronizer = new RouteRuleSynchronizer(
                new ManagementSyncProperties(new TestProperties(Map.of()).properties()),
                source,
                filter,
                registry,
                manager,
                status);
        synchronizer.disable();

        assertThat(status.state()).isEqualTo(ManagementState.DISABLED);
        synchronizer.close();
    }

    /** beta 组目标 + 兜底目标（70）的发布规则：betaWeight 即生效列。 */
    private static RouteRule betaRule(int betaWeight) {
        return new RouteRule(
                ORDER,
                "release",
                List.of(
                        new RouteRule.RouteRuleTarget(Map.of("group", "beta"), betaWeight, betaWeight),
                        new RouteRule.RouteRuleTarget(Map.of(), 70, 70)));
    }

    private static RouteRule rule(String name) {
        return new RouteRule(ORDER, name, List.of(new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 20, 80)));
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
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

    /** 测试源：手动推送事件（版本显式指定）+ 可控断流 + ack 记录。 */
    private static final class FakeSource implements RouteRuleSource {

        private final List<Session> sessions = new ArrayList<>();
        private final List<Long> ackedVersions = new ArrayList<>();
        boolean failAcks;

        @Override
        public synchronized RouteRuleWatch watch(RouteRuleListener listener) {
            Session session = new Session(listener);
            sessions.add(session);
            return session;
        }

        @Override
        public synchronized void ack(long appliedVersion) {
            if (failAcks) {
                throw new IllegalStateException("ack unavailable");
            }
            ackedVersions.add(appliedVersion);
        }

        synchronized void pushSnapshot(List<RouteRule> rules, long version) {
            sessions.forEach(session -> session.listener.onSnapshot(rules, version));
        }

        synchronized void pushUpserted(RouteRule rule, long version) {
            sessions.forEach(session -> session.listener.onUpserted(rule, version));
        }

        synchronized void pushDeleted(RouteRuleKey key, long version) {
            sessions.forEach(session -> session.listener.onDeleted(key, version));
        }

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

    private record Session(RouteRuleListener listener) implements RouteRuleWatch {
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
