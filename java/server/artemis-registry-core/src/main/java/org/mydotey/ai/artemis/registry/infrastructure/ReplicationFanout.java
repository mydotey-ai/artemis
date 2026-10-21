package org.mydotey.ai.artemis.registry.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;
import org.mydotey.ai.artemis.registry.domain.PeerReplicator;
import org.mydotey.ai.artemis.registry.domain.PermanentReplicationException;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 变更事件扇出（原产品继承的副本租约语义，架构 §4.1）：ChangeListener 锁内回调非阻塞入
 * 全局有界队列；单聚合线程 drain 当前积压（同实例变更合并、按 service 聚合、超限拆批）后
 * 投放 per-peer 有界队列；每 peer 专属 worker 串行投递，失败全抖动指数退避后重发同批
 * （契约「串行投递、重试保序」）。
 *
 * <p>队列满丢弃 + 计数（全局丢当条、peer 队列丢最旧），丢失由自愈链兜底（变更再扇出 /
 * 客户端 TTL 轮换 / bootstrap）。稳态心跳（RENEW）批量合并后复制流量与实例数解耦。
 */
@Slf4j
public final class ReplicationFanout implements ChangeListener, AutoCloseable {

    static final String KEY_QUEUE_CAPACITY = "artemis.registry.replication.queueCapacity";
    static final String KEY_PEER_QUEUE_CAPACITY = "artemis.registry.replication.peerQueueCapacity";
    static final String KEY_MAX_BATCH_CHANGES = "artemis.registry.replication.maxBatchChanges";
    static final String KEY_RETRY_INITIAL_MS = "artemis.registry.replication.retryInitialMs";
    static final String KEY_RETRY_MAX_MS = "artemis.registry.replication.retryMaxMs";

    private static final int DEFAULT_QUEUE_CAPACITY = 16_384;
    private static final int DEFAULT_PEER_QUEUE_CAPACITY = 4_096;
    private static final int DEFAULT_MAX_BATCH_CHANGES = 1_024;
    private static final long DEFAULT_RETRY_INITIAL_MS = 1_000;
    private static final long DEFAULT_RETRY_MAX_MS = 60_000;

    /** 退避指数上限：1s × 2^20 已远超 60s 封顶，防位移溢出。 */
    private static final int MAX_BACKOFF_SHIFT = 20;

    private final String selfNodeId;
    private final PeerReplicator replicator;
    private final List<PeerDelivery> peers;
    private final boolean noOp;
    private final LinkedBlockingQueue<InstanceChange> queue;
    private final Counter eventsDropped;
    /** 动态配置：消费循环内每次读取（guidelines §10.2 复制重试参数）。 */
    private final Property<String, Integer> maxBatchChanges;

    private final Property<String, Long> retryInitialMs;
    private final Property<String, Long> retryMaxMs;

    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> workers = new ArrayList<>();

    public ReplicationFanout(
            StringProperties properties,
            ClusterMembers members,
            PeerReplicator replicator,
            MeterRegistry meterRegistry) {
        this.replicator = replicator;
        this.selfNodeId = members.self().map(ClusterMember::nodeId).orElse("");
        this.maxBatchChanges = properties.getIntProperty(
                KEY_MAX_BATCH_CHANGES, new RangeValueConfig<>(DEFAULT_MAX_BATCH_CHANGES, 1, 1_000_000));
        this.retryInitialMs = properties.getLongProperty(
                KEY_RETRY_INITIAL_MS, new RangeValueConfig<>(DEFAULT_RETRY_INITIAL_MS, 1L, 600_000L));
        this.retryMaxMs = properties.getLongProperty(
                KEY_RETRY_MAX_MS, new RangeValueConfig<>(DEFAULT_RETRY_MAX_MS, 1L, 3_600_000L));
        this.eventsDropped = Counter.builder("artemis.registry.replication.events.dropped")
                .description("Change events dropped when the global replication queue is full")
                .register(meterRegistry);

        List<PeerDelivery> targets = new ArrayList<>();
        for (ClusterMember member : members.members()) {
            if (!member.nodeId().equals(selfNodeId)) {
                targets.add(new PeerDelivery(member, properties, meterRegistry));
            }
        }
        this.peers = List.copyOf(targets);
        this.noOp = peers.isEmpty(); // 成员表只含自身：无扇出目标，不建队列与线程
        this.queue = noOp
                ? null
                : new LinkedBlockingQueue<>(ScfProperties.staticInt(
                                properties,
                                KEY_QUEUE_CAPACITY,
                                DEFAULT_QUEUE_CAPACITY,
                                1,
                                10_000_000,
                                "global replication event queue capacity (static)")
                        .getValue());
    }

    /** 生命周期入口（装配层调用，等价 initMethod=start）。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("ReplicationFanout already started");
        }
        if (noOp) {
            return;
        }
        Thread aggregator =
                Thread.ofVirtual().name("artemis-replication-aggregator").start(this::aggregate);
        workers.add(aggregator);
        for (PeerDelivery peer : peers) {
            workers.add(Thread.ofVirtual()
                    .name("artemis-replication-peer-" + peer.member.nodeId())
                    .start(() -> deliver(peer)));
        }
    }

    /**
     * 锁内回调契约（{@link ChangeListener}）：非阻塞 offer；队列满丢当条 + 计数
     * （丢失由自愈链兜底），单事件异常由聚合侧 fire 隔离、不会抛出。
     */
    @Override
    public void onChange(InstanceChange change) {
        if (noOp || !running.get()) {
            return;
        }
        if (!queue.offer(change)) {
            eventsDropped.increment();
            log.warn("replication queue full, change dropped: {}", change);
        }
    }

    @Override
    public void close() {
        running.set(false);
        // 中断阻塞的 take/sleep；未投递事件丢弃（重启后自愈链恢复），channel 释放归 PeerReplicator
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }

    /** 聚合循环：take 首条 → drain 当前积压 → 合并拆批 → 投放全部 peer 队列。 */
    private void aggregate() {
        while (running.get()) {
            List<InstanceChange> drained = new ArrayList<>();
            try {
                drained.add(queue.take());
                queue.drainTo(drained);
            } catch (InterruptedException e) {
                return; // 关闭中断
            }
            try {
                for (ReplicationBatch batch : buildBatches(drained, selfNodeId, maxBatchChanges.getValue())) {
                    for (PeerDelivery peer : peers) {
                        peer.enqueue(batch);
                    }
                }
            } catch (Throwable e) {
                log.warn("replication batch building failed, changes lost to self-healing chain", e);
            }
        }
    }

    /** 同 (key, instanceId) 后到覆盖先到（产生序内后到 = 更新终态），按产生序拆批。包私有供确定性单测。 */
    static List<ReplicationBatch> buildBatches(List<InstanceChange> drained, String selfNodeId, int maxBatch) {
        LinkedHashMap<InstanceKey, InstanceChange> merged = new LinkedHashMap<>();
        for (InstanceChange change : drained) {
            merged.put(new InstanceKey(change.key(), change.instance().instanceId()), change);
        }
        List<ReplicationBatch> batches = new ArrayList<>();
        List<InstanceChange> current = new ArrayList<>(Math.min(maxBatch, merged.size()));
        for (InstanceChange change : merged.values()) {
            current.add(change);
            if (current.size() >= maxBatch) {
                batches.add(new ReplicationBatch(selfNodeId, List.copyOf(current)));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            batches.add(new ReplicationBatch(selfNodeId, List.copyOf(current)));
        }
        return batches;
    }

    /** per-peer 串行投递循环：失败退避重发同批（保序），成功后取下一批。 */
    private void deliver(PeerDelivery peer) {
        while (running.get()) {
            ReplicationBatch batch;
            try {
                batch = peer.queue.take();
            } catch (InterruptedException e) {
                return; // 关闭中断
            }
            int attempts = 0;
            while (running.get()) {
                try {
                    replicator.send(peer.member, batch);
                    peer.sent.increment();
                    if (attempts > 0) {
                        log.info("replication to peer {} recovered after {} attempts", peer.member.nodeId(), attempts);
                    }
                    break;
                } catch (PermanentReplicationException e) {
                    // 重试同批永无成功（批次非法 / peer 版本或凭证不匹配）：放弃本批继续后续，
                    // 否则队头永久阻塞会让该 peer 的全部后续复制静默停摆
                    peer.rejected.increment();
                    log.warn(
                            "replication batch permanently rejected by peer {}, batch dropped to self-healing chain",
                            peer.member.nodeId(),
                            e);
                    break;
                } catch (Throwable e) {
                    attempts++;
                    peer.failed.increment();
                    if (attempts == 1) {
                        log.warn("replication to peer {} failed, will retry with backoff", peer.member.nodeId(), e);
                    }
                    try {
                        Thread.sleep(backoffDelayMs(attempts));
                    } catch (InterruptedException ie) {
                        return; // 关闭中断
                    }
                }
            }
        }
    }

    /** 全抖动指数退避：random(0, min(retryMax, retryInitial × 2^(attempts-1)))。 */
    private long backoffDelayMs(int attempts) {
        long base = retryInitialMs.getValue() * (1L << Math.min(attempts - 1, MAX_BACKOFF_SHIFT));
        return (long) (ThreadLocalRandom.current().nextDouble(0, Math.min(base, retryMaxMs.getValue())));
    }

    /** 单 peer 投递状态：有界批队列（满丢最旧 + 计数）+ 就地指标。 */
    private static final class PeerDelivery {

        private final ClusterMember member;
        private final LinkedBlockingQueue<ReplicationBatch> queue;
        private final Counter sent;
        private final Counter failed;
        private final Counter rejected;
        private final Counter dropped;

        private PeerDelivery(ClusterMember member, StringProperties properties, MeterRegistry meterRegistry) {
            this.member = member;
            this.queue = new LinkedBlockingQueue<>(ScfProperties.staticInt(
                            properties,
                            KEY_PEER_QUEUE_CAPACITY,
                            DEFAULT_PEER_QUEUE_CAPACITY,
                            1,
                            1_000_000,
                            "per-peer replication batch queue capacity (static)")
                    .getValue());
            this.sent = Counter.builder("artemis.registry.replication.batches.sent")
                    .tag("peer", member.nodeId())
                    .description("Replication batches successfully delivered to the peer")
                    .register(meterRegistry);
            this.failed = Counter.builder("artemis.registry.replication.batches.failed")
                    .tag("peer", member.nodeId())
                    .description("Replication delivery attempts failed for the peer")
                    .register(meterRegistry);
            this.rejected = Counter.builder("artemis.registry.replication.batches.rejected")
                    .tag("peer", member.nodeId())
                    .description("Replication batches permanently rejected by the peer (dropped, not retried)")
                    .register(meterRegistry);
            this.dropped = Counter.builder("artemis.registry.replication.batches.dropped")
                    .tag("peer", member.nodeId())
                    .description("Replication batches dropped (oldest first) when the peer queue is full")
                    .register(meterRegistry);
        }

        /** 单生产者（聚合线程）单消费者（worker）下 poll+offer 循环安全；满丢最旧 + 计数。 */
        private void enqueue(ReplicationBatch batch) {
            while (!queue.offer(batch)) {
                ReplicationBatch stale = queue.poll();
                if (stale == null) {
                    continue; // 消费者刚取走一条，重试 offer
                }
                dropped.increment();
                log.warn("peer {} replication queue full, oldest batch dropped to self-healing chain", member.nodeId());
            }
        }
    }
}
