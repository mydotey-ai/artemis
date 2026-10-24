package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class EjectionRuleDispatcherTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private final InMemoryEjectionStore store = new InMemoryEjectionStore();
    private final EjectionRuleDispatcher dispatcher = new EjectionRuleDispatcher(store);

    @Test
    void should_push_snapshot_then_deltas_in_order() {
        store.eject(new EjectionRecord(new InstanceKey(SERVICE, "i1"), "r1", NOW));
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        dispatcher.onEjected(new EjectionRecord(new InstanceKey(SERVICE, "i2"), "r2", NOW));
        dispatcher.onRecovered(new InstanceKey(SERVICE, "i1"));

        assertThat(subscriber.events).containsExactly("snapshot:i1", "ejected:i2", "recovered:i1");
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
        EjectionRecord first = new EjectionRecord(new InstanceKey(SERVICE, "i1"), "r1", NOW);
        EjectionRecord second = new EjectionRecord(new InstanceKey(SERVICE, "i2"), "r2", NOW);
        store.eject(first);
        dispatcher.onEjected(first);
        store.eject(second);
        dispatcher.onEjected(second);

        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:i1,i2");
    }

    @Test
    void should_disconnect_subscriber_on_push_failure() {
        RecordingSubscriber failing = new RecordingSubscriber();
        failing.failOnEjected = true;
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(failing);
        dispatcher.subscribe(healthy);

        dispatcher.onEjected(new EjectionRecord(new InstanceKey(SERVICE, "i9"), "r", NOW));

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

    private static final class RecordingSubscriber implements EjectionRuleSubscriber {

        final List<String> events = new ArrayList<>();
        boolean failOnEjected;
        boolean closed;

        @Override
        public void onSnapshot(List<EjectionRecord> records) {
            events.add("snapshot:"
                    + records.stream()
                            .map(record -> record.instance().instanceId())
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
        }

        @Override
        public void onEjected(EjectionRecord record) {
            if (failOnEjected) {
                throw new IllegalStateException("boom");
            }
            events.add("ejected:" + record.instance().instanceId());
        }

        @Override
        public void onRecovered(InstanceKey instance) {
            events.add("recovered:" + instance.instanceId());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
