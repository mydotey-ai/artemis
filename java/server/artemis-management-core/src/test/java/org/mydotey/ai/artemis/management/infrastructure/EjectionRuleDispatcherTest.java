package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.EjectionAction;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class EjectionRuleDispatcherTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private final InMemoryEjectionStore store = new InMemoryEjectionStore();
    private final EjectionRuleDispatcher dispatcher = new EjectionRuleDispatcher(store);

    @Test
    void should_push_snapshot_then_deltas_in_order() {
        store.append(eject(EjectionTarget.instance(SERVICE, "i1"), "r1"));
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        dispatcher.onEjected(eject(EjectionTarget.instance(SERVICE, "i2"), "r2"));
        dispatcher.onRecovered(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"));

        assertThat(subscriber.events).containsExactly("snapshot:i1", "ejected:i2", "recovered:zone:dc-2");
    }

    @Test
    void should_push_empty_snapshot_when_no_ejections() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:");
    }

    @Test
    void should_see_snapshot_including_ejection_during_subscribe() {
        // 摘除与订阅并发：单锁全序——无论先后，快照与增量不重叠不丢失
        // （onEjected 前先落库，对齐 ManagementServiceImpl 的调用序）
        RecordingSubscriber subscriber = new RecordingSubscriber();
        EjectionRecord first = store.append(eject(EjectionTarget.instance(SERVICE, "i1"), "r1"));
        dispatcher.onEjected(first);
        EjectionRecord second = store.append(eject(EjectionTarget.instance(SERVICE, "i2"), "r2"));
        dispatcher.onEjected(second);

        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:i1,i2");
    }

    @Test
    void should_send_active_rules_only_in_snapshot() {
        // 已对消目标不进快照（对消后 active 集不含）
        store.append(eject(EjectionTarget.instance(SERVICE, "i1"), "r1"));
        store.append(recover(EjectionTarget.instance(SERVICE, "i1")));
        store.append(eject(EjectionTarget.selector(EjectionScope.SERVER, "10.1.1.1"), "host down"));

        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:server:10.1.1.1");
    }

    @Test
    void should_disconnect_subscriber_on_push_failure() {
        RecordingSubscriber failing = new RecordingSubscriber();
        failing.failOnEjected = true;
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(failing);
        dispatcher.subscribe(healthy);

        dispatcher.onEjected(eject(EjectionTarget.instance(SERVICE, "i9"), "r"));

        assertThat(healthy.events).containsExactly("snapshot:", "ejected:i9");
        assertThat(failing.events).containsExactly("snapshot:"); // 断流注销后不再收到
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

    private static EjectionRecord eject(EjectionTarget target, String reason) {
        return new EjectionRecord(target, EjectionAction.EJECT, reason, "admin", NOW, 0);
    }

    private static EjectionRecord recover(EjectionTarget target) {
        return new EjectionRecord(target, EjectionAction.RECOVER, null, "admin", NOW, 0);
    }

    private static final class RecordingSubscriber implements EjectionRuleSubscriber {

        final List<String> events = new ArrayList<>();
        boolean failOnEjected;
        boolean closed;

        @Override
        public void onSnapshot(List<EjectionRecord> records) {
            events.add("snapshot:"
                    + records.stream()
                            .map(record -> record.target().scope() == EjectionScope.INSTANCE
                                    ? record.target().instanceId()
                                    : record.target().scope().name().toLowerCase() + ":"
                                            + record.target().value())
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
        }

        @Override
        public void onEjected(EjectionRecord record) {
            if (failOnEjected) {
                throw new IllegalStateException("boom");
            }
            events.add("ejected:" + record.target().instanceId());
        }

        @Override
        public void onRecovered(EjectionTarget target) {
            events.add("recovered:"
                    + (target.scope() == EjectionScope.INSTANCE
                            ? target.instanceId()
                            : target.scope().name().toLowerCase() + ":" + target.value()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
