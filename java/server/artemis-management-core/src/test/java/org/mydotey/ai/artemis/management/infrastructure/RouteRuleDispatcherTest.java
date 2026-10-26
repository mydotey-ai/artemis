package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleStatus;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.RouteRuleTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/** 路由规则分发（phase-2）：快照 ACTIVE 过滤 + tick 版本单调 + 断流注销（模式同 ejection）。 */
class RouteRuleDispatcherTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");

    private final InMemoryRouteRuleStore store = new InMemoryRouteRuleStore();
    private final RouteRuleDispatcher dispatcher = new RouteRuleDispatcher(store);

    @Test
    void should_snapshot_active_rules_with_version_when_subscribe() {
        store.insert(rule("beta", RouteRuleStatus.ACTIVE));
        store.insert(rule("inactive", RouteRuleStatus.INACTIVE));
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        // 无变更订阅：版本 0，快照仅含 ACTIVE
        assertThat(subscriber.events).containsExactly("snapshot:v0:beta");
    }

    @Test
    void should_push_increment_and_advance_tick_on_change() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        dispatcher.onUpserted(rule("beta", RouteRuleStatus.ACTIVE));
        dispatcher.onDeleted(SERVICE, "beta");
        dispatcher.onUpserted(rule("beta", RouteRuleStatus.INACTIVE)); // INACTIVE 化经 deleted 语义（应用层判定）

        assertThat(subscriber.events)
                .containsExactly("snapshot:v0:", "upserted:v1:beta", "deleted:v2:beta", "deleted:v3:beta");
        assertThat(dispatcher.version()).isEqualTo(3);
    }

    @Test
    void should_see_snapshot_including_change_during_subscribe() {
        store.insert(rule("beta", RouteRuleStatus.ACTIVE));
        dispatcher.onUpserted(store.find(SERVICE, "beta"));
        RecordingSubscriber subscriber = new RecordingSubscriber();

        dispatcher.subscribe(subscriber);

        // 订阅前变更已在 store：快照含全量、版本对齐 tick（无半增量窗口）
        assertThat(subscriber.events).containsExactly("snapshot:v1:beta");
    }

    @Test
    void should_disconnect_subscriber_on_push_failure() {
        RecordingSubscriber failing = new RecordingSubscriber();
        failing.failOnUpserted = true;
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(failing);
        dispatcher.subscribe(healthy);

        dispatcher.onUpserted(rule("beta", RouteRuleStatus.ACTIVE));

        assertThat(healthy.events).containsExactly("snapshot:v0:", "upserted:v1:beta");
        assertThat(failing.events).containsExactly("snapshot:v0:"); // 断流注销后不再收到
        assertThat(failing.closed).isTrue();
    }

    @Test
    void should_close_all_subscribers_on_close() {
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();
        dispatcher.subscribe(first);
        dispatcher.subscribe(second);

        dispatcher.close();

        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
    }

    private static RouteRule rule(String name, RouteRuleStatus status) {
        return new RouteRule(
                SERVICE, name, status, List.of(new RouteRuleTarget(Map.of("group", "beta"), 20, 80)), null, null, null);
    }

    private static final class RecordingSubscriber implements RouteRuleSubscriber {

        final List<String> events = new ArrayList<>();
        boolean failOnUpserted;
        boolean closed;

        @Override
        public void onSnapshot(List<RouteRule> rules, long version) {
            events.add("snapshot:v" + version + ":"
                    + rules.stream()
                            .map(RouteRule::name)
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
        }

        @Override
        public void onUpserted(RouteRule rule, long version) {
            if (failOnUpserted) {
                throw new IllegalStateException("boom");
            }
            events.add("upserted:v" + version + ":" + rule.name());
        }

        @Override
        public void onDeleted(ServiceKey service, String name, long version) {
            events.add("deleted:v" + version + ":" + name);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
