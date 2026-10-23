package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriber;
import org.mydotey.ai.artemis.registry.domain.ServiceChange;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 投影分发：消费方语义收敛（RENEW 过滤、EVICTED→DELETE）、异步组批、慢消费者断流。 */
class ProjectionDispatcherTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ProjectionDispatcher> dispatchers = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        dispatchers.forEach(ProjectionDispatcher::close);
    }

    // ---- 组批与语义收敛（确定性直测） ----

    @Test
    void should_merge_and_group_changes_by_service() {
        ServiceKey other = new ServiceKey("default", "pay-service");
        List<List<ServiceChange>> batches = ProjectionDispatcher.buildBatches(
                List.of(
                        change(KEY, "i1", ChangeType.NEW),
                        change(KEY, "i1", ChangeType.UPDATE),
                        change(other, "p1", ChangeType.NEW)),
                1024);

        assertThat(batches).singleElement().satisfies(batch -> {
            assertThat(batch).extracting(ServiceChange::key).containsExactly(KEY, other);
            assertThat(batch.get(0).changes())
                    .singleElement()
                    .satisfies(change -> assertThat(change.type()).isEqualTo(ChangeType.UPDATE));
            assertThat(batch.get(1).changes())
                    .singleElement()
                    .satisfies(
                            change -> assertThat(change.instance().instanceId()).isEqualTo("p1"));
        });
    }

    @Test
    void should_map_evicted_to_delete() {
        List<List<ServiceChange>> batches =
                ProjectionDispatcher.buildBatches(List.of(change(KEY, "i1", ChangeType.EVICTED)), 1024);

        assertThat(batches)
                .singleElement()
                .satisfies(batch -> assertThat(batch.get(0).changes())
                        .singleElement()
                        .extracting(InstanceChange::type)
                        .isEqualTo(ChangeType.DELETE));
    }

    @Test
    void should_split_batches_on_change_limit() {
        ServiceKey other = new ServiceKey("default", "pay-service");
        List<List<ServiceChange>> batches = ProjectionDispatcher.buildBatches(
                List.of(
                        change(KEY, "i1", ChangeType.NEW),
                        change(KEY, "i2", ChangeType.NEW),
                        change(other, "p1", ChangeType.NEW),
                        change(other, "p2", ChangeType.NEW)),
                2);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).extracting(ServiceChange::key).containsExactly(KEY);
        assertThat(batches.get(1)).extracting(ServiceChange::key).containsExactly(other);
    }

    // ---- 异步投递路径 ----

    @Test
    void should_dispatch_changes_to_subscriber_and_filter_renew() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        ProjectionDispatcher dispatcher = startedDispatcher(Map.of());
        dispatcher.subscribe(subscriber);

        dispatcher.onChange(change(KEY, "i1", ChangeType.NEW));
        dispatcher.onChange(change(KEY, "i1", ChangeType.RENEW));
        dispatcher.onChange(change(KEY, "i1", ChangeType.UPDATE));
        dispatcher.onChange(change(KEY, "i2", ChangeType.EVICTED));

        // 最终状态流：RENEW 被过滤、EVICTED 映射 DELETE；同实例组批合并与否不影响末态
        TestAwait.await("delete for i2 delivered", () -> lastTypeIs(subscriber.changes(), "i2", ChangeType.DELETE));
        TestAwait.await("update for i1 delivered", () -> lastTypeIs(subscriber.changes(), "i1", ChangeType.UPDATE));
        assertThat(subscriber.changes())
                .extracting(InstanceChange::type)
                .doesNotContain(ChangeType.RENEW, ChangeType.EVICTED);
    }

    @Test
    void should_send_empty_establishment_batch_before_any_change() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        ProjectionDispatcher dispatcher = startedDispatcher(Map.of());
        dispatcher.subscribe(subscriber);
        dispatcher.onChange(change(KEY, "i1", ChangeType.NEW));

        TestAwait.await("change delivered", () -> subscriber.changes().size() == 1);
        // 契约：订阅建立后首条为空批（消费方据此开始 Bootstrap）
        assertThat(subscriber.batches())
                .first()
                .satisfies(batch -> assertThat(batch).isEmpty());
    }

    @Test
    void should_disconnect_slow_subscriber_when_queue_full() throws Exception {
        BlockingSubscriber slow = new BlockingSubscriber();
        ProjectionDispatcher dispatcher = startedDispatcher(Map.of(
                ProjectionDispatcher.KEY_SUBSCRIBER_QUEUE_CAPACITY, "1",
                ProjectionDispatcher.KEY_MAX_BATCH_CHANGES, "1")); // 每变更一批
        dispatcher.subscribe(slow);

        dispatcher.onChange(change(KEY, "i1", ChangeType.NEW));
        slow.awaitBlocked(); // worker 取走批 1 并阻塞在回调
        dispatcher.onChange(change(KEY, "i2", ChangeType.NEW)); // 入队（容量 1）
        dispatcher.onChange(change(KEY, "i3", ChangeType.NEW)); // 满 → 断流

        TestAwait.await("slow subscriber disconnected", slow::isClosed);
        assertThat(meters.get("artemis.registry.projection.subscribers.disconnected")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // 断流不影响分发管线：后续订阅者照常接收
        slow.unblock();
        RecordingSubscriber healthy = new RecordingSubscriber();
        dispatcher.subscribe(healthy);
        dispatcher.onChange(change(KEY, "i4", ChangeType.NEW));
        TestAwait.await(
                "subsequent subscriber keeps receiving", () -> healthy.changes().size() == 1);
        assertThat(healthy.changes().get(0).instance().instanceId()).isEqualTo("i4");
    }

    @Test
    void should_not_deliver_after_unsubscribe() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        ProjectionDispatcher dispatcher = startedDispatcher(Map.of());
        dispatcher.subscribe(subscriber);
        dispatcher.unsubscribe(subscriber);

        dispatcher.onChange(change(KEY, "i1", ChangeType.NEW));

        // 注销后服务端零投递；用一次有订阅者的对照投递确认管线仍在工作
        RecordingSubscriber control = new RecordingSubscriber();
        dispatcher.subscribe(control);
        dispatcher.onChange(change(KEY, "i2", ChangeType.NEW));
        TestAwait.await("control subscriber receives", () -> control.changes().size() == 1);

        assertThat(subscriber.changes()).isEmpty();
        assertThat(subscriber.isClosed()).isTrue();
    }

    // ---- 设施 ----

    private ProjectionDispatcher startedDispatcher(Map<String, String> configOverrides) {
        ProjectionDispatcher dispatcher =
                new ProjectionDispatcher(TestProperties.of(configOverrides).properties(), meters);
        dispatcher.start();
        dispatchers.add(dispatcher);
        return dispatcher;
    }

    private static boolean lastTypeIs(List<InstanceChange> changes, String instanceId, ChangeType expected) {
        ChangeType last = null;
        for (InstanceChange change : changes) {
            if (change.instance().instanceId().equals(instanceId)) {
                last = change.type();
            }
        }
        return last == expected;
    }

    private static InstanceChange change(ServiceKey key, String instanceId, ChangeType type) {
        return new InstanceChange(key, instance(instanceId), type, 0L);
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    /** 记录全部到达的变更（跨批展平）。 */
    private static class RecordingSubscriber implements ProjectionSubscriber {

        private final List<InstanceChange> batches = new CopyOnWriteArrayList<>();
        private final List<List<ServiceChange>> batchesSeen = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void onChanges(List<ServiceChange> batch) {
            batchesSeen.add(List.copyOf(batch));
            batch.forEach(service -> batches.addAll(service.changes()));
        }

        @Override
        public void close() {
            closed.set(true);
        }

        List<InstanceChange> changes() {
            return batches;
        }

        List<List<ServiceChange>> batches() {
            return batchesSeen;
        }

        boolean isClosed() {
            return closed.get();
        }
    }

    /** 首条回调即阻塞的慢消费者（触发订阅者队列满）。 */
    private static final class BlockingSubscriber extends RecordingSubscriber {

        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void onChanges(List<ServiceChange> batch) {
            blocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.onChanges(batch);
        }

        private void awaitBlocked() throws InterruptedException {
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void unblock() {
            release.countDown();
        }
    }
}
