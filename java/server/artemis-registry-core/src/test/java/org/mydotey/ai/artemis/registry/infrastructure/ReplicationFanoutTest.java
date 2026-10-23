package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.PeerReplicator;
import org.mydotey.ai.artemis.registry.domain.PermanentReplicationException;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 扇出编排：合并拆批（确定性直测）+ 异步投递路径（fake replicator 观察重试保序与丢弃策略）。 */
class ReplicationFanoutTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");
    private static final ClusterMember SELF = new ClusterMember(
            "node-a", new ClusterEndpoint("127.0.0.1", 19093), new ClusterEndpoint("127.0.0.1", 19090));
    private static final ClusterMember PEER = new ClusterMember(
            "node-b", new ClusterEndpoint("127.0.0.1", 19094), new ClusterEndpoint("127.0.0.1", 19091));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ReplicationFanout> fanouts = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        fanouts.forEach(ReplicationFanout::close);
    }

    // ---- buildBatches（确定性直测）----

    @Test
    void should_merge_same_instance_changes_in_batch() {
        List<ReplicationBatch> batches = ReplicationFanout.buildBatches(
                List.of(
                        change("i1", ChangeType.RENEW),
                        change("i1", ChangeType.UPDATE),
                        change("i2", ChangeType.RENEW)),
                "node-a",
                1024);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).sourceNodeId()).isEqualTo("node-a");
        assertThat(batches.get(0).changes())
                .extracting(InstanceChange::type)
                .containsExactly(ChangeType.UPDATE, ChangeType.RENEW); // i1 后到 UPDATE 胜 RENEW；i2 独立
    }

    @Test
    void should_split_batches_on_limit_in_emission_order() {
        ServiceKey other = new ServiceKey("default", "pay-service");
        List<InstanceChange> drained = List.of(
                change(KEY, "i1", ChangeType.NEW),
                change(other, "p1", ChangeType.NEW),
                change(KEY, "i2", ChangeType.NEW),
                change(other, "p2", ChangeType.NEW));

        List<ReplicationBatch> batches = ReplicationFanout.buildBatches(drained, "node-a", 2);

        // 产生序保持（i1,p1,i2,p2），超限拆批：同实例不因拆批乱序（各批内同 key 保持产生序）
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).changes())
                .extracting(c -> c.instance().instanceId())
                .containsExactly("i1", "p1");
        assertThat(batches.get(1).changes())
                .extracting(c -> c.instance().instanceId())
                .containsExactly("i2", "p2");
    }

    // ---- 消费方语义过滤 ----

    @Test
    void should_not_replicate_evicted_event() {
        RecordingReplicator replicator = new RecordingReplicator();
        ReplicationFanout fanout = startedFanout(Map.of(), replicator);

        fanout.onChange(change("i1", ChangeType.EVICTED));
        fanout.onChange(change("i2", ChangeType.NEW));

        TestAwait.await("only NEW delivered", () -> replicator.delivered.size() == 1);
        assertThat(replicator.delivered.get(0).changes())
                .extracting(c -> c.instance().instanceId())
                .containsExactly("i2");
    }

    // ---- 异步投递路径 ----

    @Test
    void should_retry_same_batch_with_backoff_when_send_fails() throws Exception {
        FlakyReplicator replicator = new FlakyReplicator(2); // 前 2 次失败
        ReplicationFanout fanout = startedFanout(Map.of(ReplicationFanout.KEY_RETRY_INITIAL_MS, "10"), replicator);

        fanout.onChange(change("i1", ChangeType.NEW));

        TestAwait.await("delivery succeeds after retries", () -> replicator.delivered.size() == 1);
        assertThat(replicator.attempts.get()).isEqualTo(3); // 2 失败 + 1 成功
        // 重发为同一批（保序：失败批次不被后续批次超越）
        assertThat(replicator.seenBatches).hasSize(3).allSatisfy(b -> b.equals(replicator.seenBatches.get(0)));
        assertThat(meters.get("artemis.registry.replication.batches.failed")
                        .tag("peer", "node-b")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void should_drop_batch_and_continue_when_permanently_rejected() {
        RecordingReplicator replicator = new RecordingReplicator();
        replicator.permanentlyReject = true; // 首批永久拒绝（如滚动升级窗口 UNIMPLEMENTED）
        ReplicationFanout fanout = startedFanout(Map.of(ReplicationFanout.KEY_MAX_BATCH_CHANGES, "1"), replicator);

        fanout.onChange(change("i1", ChangeType.NEW));
        TestAwait.await(
                "first batch permanently rejected",
                () -> meters.get("artemis.registry.replication.batches.rejected")
                                .tag("peer", "node-b")
                                .counter()
                                .count()
                        == 1.0);

        fanout.onChange(change("i2", ChangeType.NEW)); // 前批丢弃后必须继续投递，不得队头阻塞
        TestAwait.await(
                "subsequent batch delivered after permanent rejection",
                () -> replicator.delivered.size() == 1
                        && replicator
                                .delivered
                                .get(0)
                                .changes()
                                .get(0)
                                .instance()
                                .instanceId()
                                .equals("i2"));
    }

    @Test
    void should_drop_oldest_and_count_when_peer_queue_full() throws Exception {
        BlockingReplicator replicator = new BlockingReplicator();
        ReplicationFanout fanout = startedFanout(
                Map.of(
                        ReplicationFanout.KEY_PEER_QUEUE_CAPACITY, "1",
                        ReplicationFanout.KEY_MAX_BATCH_CHANGES, "1"), // 每变更一批
                replicator);

        // 第一步：批 1 被 worker 取走并在 send 阻塞（队首占住，peer 队列此时为空）
        fanout.onChange(change("i1", ChangeType.NEW));
        replicator.awaitBlocked();

        // 第二步：批 2 入队（容量 1）；批 3 入队丢最旧（批 2）
        fanout.onChange(change("i2", ChangeType.NEW));
        fanout.onChange(change("i3", ChangeType.NEW));
        TestAwait.await(
                "oldest batch dropped when peer queue full",
                () -> meters.get("artemis.registry.replication.batches.dropped")
                                .tag("peer", "node-b")
                                .counter()
                                .count()
                        == 1.0);

        replicator.unblock();
        TestAwait.await("remaining batches delivered", () -> replicator.delivered.size() == 2);
        assertThat(replicator.delivered)
                .extracting(batch -> batch.changes().get(0).instance().instanceId())
                .containsExactly("i1", "i3"); // 批 2（i2）被丢弃
    }

    // ---- 设施 ----

    private ReplicationFanout startedFanout(Map<String, String> configOverrides, PeerReplicator replicator) {
        ReplicationFanout fanout = new ReplicationFanout(
                TestProperties.of(configOverrides).properties(), twoNodeMembers(), replicator, meters);
        fanout.start();
        fanouts.add(fanout);
        return fanout;
    }

    private static ClusterMembers twoNodeMembers() {
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of(SELF, PEER);
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(SELF);
            }
        };
    }

    private static InstanceChange change(String instanceId, ChangeType type) {
        return change(KEY, instanceId, type);
    }

    private static InstanceChange change(ServiceKey key, String instanceId, ChangeType type) {
        return new InstanceChange(
                key, new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of()), type, 0L);
    }

    /** 记录全部成功交付的 replicator；可编程首批永久拒绝（批次级确定性失败）。 */
    private static final class RecordingReplicator implements PeerReplicator {

        private final List<ReplicationBatch> delivered = new CopyOnWriteArrayList<>();
        private volatile boolean permanentlyReject;

        @Override
        public void send(ClusterMember peer, ReplicationBatch batch) {
            if (permanentlyReject) {
                permanentlyReject = false; // 仅首批拒绝
                throw new PermanentReplicationException("programmed permanent rejection");
            }
            delivered.add(batch);
        }
    }

    /** 可编程失败次数的 replicator：记录每次投递的 batch（含失败尝试）与成功交付。 */
    private static final class FlakyReplicator implements PeerReplicator {

        private final AtomicInteger failuresLeft;
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<ReplicationBatch> delivered = new CopyOnWriteArrayList<>();
        private final List<ReplicationBatch> seenBatches = new CopyOnWriteArrayList<>();

        private FlakyReplicator(int failures) {
            this.failuresLeft = new AtomicInteger(failures);
        }

        @Override
        public void send(ClusterMember peer, ReplicationBatch batch) {
            seenBatches.add(batch);
            attempts.incrementAndGet();
            if (failuresLeft.getAndAccumulate(1, (left, inc) -> Math.max(0, left - inc)) > 0) {
                throw new IllegalStateException("programmed failure");
            }
            delivered.add(batch);
        }
    }

    /** 首次 send 阻塞（占住 worker），unblock 后放行。 */
    private static final class BlockingReplicator implements PeerReplicator {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<ReplicationBatch> delivered = new CopyOnWriteArrayList<>();

        @Override
        public void send(ClusterMember peer, ReplicationBatch batch) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            delivered.add(batch);
        }

        void awaitBlocked() throws InterruptedException {
            if (!entered.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("replicator not entered within 10s");
            }
        }

        void unblock() {
            release.countDown();
        }
    }
}
