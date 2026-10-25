package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriber;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class LogicalInstanceDispatcherTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");

    private final InMemoryLogicalInstanceStore store = new InMemoryLogicalInstanceStore();
    private final LogicalInstanceDispatcher dispatcher = new LogicalInstanceDispatcher(store);

    @Test
    void should_push_snapshot_then_increments_in_order() {
        store.upsert(logical("i1"));
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        dispatcher.onUpserted(logical("i2"));
        dispatcher.onDeleted(SERVICE, "i1");

        assertThat(subscriber.events).containsExactly("snapshot:i1", "upserted:i2", "deleted:i1");
    }

    @Test
    void should_push_empty_snapshot_when_no_logical_instances() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:");
    }

    @Test
    void should_see_snapshot_including_change_during_subscribe() {
        // 变更与订阅并发：单锁全序——无论先后，快照与增量不重叠不丢失
        // （onUpserted 前先落库，对齐 ManagementServiceImpl 的调用序）
        store.upsert(logical("i1"));
        dispatcher.onUpserted(logical("i1"));

        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        assertThat(subscriber.events).containsExactly("snapshot:i1");
    }

    @Test
    void should_disconnect_subscriber_on_push_failure() {
        RecordingSubscriber failing = new RecordingSubscriber();
        failing.failOnUpserted = true;
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(failing);
        dispatcher.subscribe(healthy);

        dispatcher.onUpserted(logical("i9"));

        assertThat(healthy.events).containsExactly("snapshot:", "upserted:i9");
        assertThat(failing.events).containsExactly("snapshot:"); // 断流注销后不再收到
        assertThat(dispatcher.size()).isEqualTo(1);
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
        assertThat(dispatcher.size()).isZero();
    }

    private static LogicalInstance logical(String instanceId) {
        return new LogicalInstance(
                SERVICE, new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of()));
    }

    private static final class RecordingSubscriber implements LogicalInstanceSubscriber {

        final List<String> events = new ArrayList<>();
        boolean failOnUpserted;
        boolean closed;

        @Override
        public void onSnapshot(List<LogicalInstance> instances) {
            events.add("snapshot:"
                    + instances.stream()
                            .map(instance -> instance.instance().instanceId())
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
        }

        @Override
        public void onUpserted(LogicalInstance instance) {
            if (failOnUpserted) {
                throw new IllegalStateException("push failed");
            }
            events.add("upserted:" + instance.instance().instanceId());
        }

        @Override
        public void onDeleted(ServiceKey service, String instanceId) {
            events.add("deleted:" + instanceId);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
