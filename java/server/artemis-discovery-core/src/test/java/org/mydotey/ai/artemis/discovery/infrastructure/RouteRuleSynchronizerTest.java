package org.mydotey.ai.artemis.discovery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.TestProperties;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleKey;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleListener;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleSource;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleWatch;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;

/** 路由规则同步引擎（phase-2，只缓存不执行）：应用 + ack 推进、断连保留缓存（fail-open）。 */
class RouteRuleSynchronizerTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");

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
                new ManagementSyncProperties(new TestProperties(Map.of()).properties()), source, status);
        synchronizer.disable();

        assertThat(status.state()).isEqualTo(ManagementState.DISABLED);
        synchronizer.close();
    }

    private static RouteRule rule(String name) {
        return new RouteRule(ORDER, name, List.of(new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 20, 80)));
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
}
