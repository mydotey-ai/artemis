package org.mydotey.ai.artemis.registry.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerBootstrapSource;
import org.mydotey.ai.artemis.registry.domain.PeerStatusView;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 后台 peer 状态探测：单线程调度（首轮立即、固定延迟），每轮用虚拟线程并发探测全部 peer，
 * 整轮与单次调用都有超时；失败/超时/nodeId 不匹配统一记为 {@link NodeState#UNKNOWN}。
 *
 * <p>结果每轮整体重建并经 volatile 不可变 Map 发布（读侧无锁）。探测只服务节点状态 API /
 * 观测，不参与复制扇出门控（复制按 per-peer 队列独立重试）。单节点模式由装配层不启动调度。
 */
@Slf4j
public final class ClusterNodeStatusMonitor implements PeerStatusView, AutoCloseable {

    static final String KEY_PROBE_INTERVAL_MS = "artemis.registry.cluster.probeIntervalMs";
    static final String KEY_PROBE_TIMEOUT_MS = "artemis.registry.cluster.probeTimeoutMs";

    private static final long DEFAULT_PROBE_INTERVAL_MS = 5_000;
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 1_000;

    private final ClusterMembers clusterMembers;
    private final PeerBootstrapSource peerSource;
    private final Property<String, Long> probeTimeoutMs;
    private final ExecutorService probeExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("artemis-peer-probe-", 0).factory());
    private final AbstractIntervalScheduler scheduler;
    private final MeterRegistry meterRegistry;
    private final Counter probeRounds;
    private final ConcurrentHashMap<String, Counter> failureCounters = new ConcurrentHashMap<>();

    /** 不可变快照，volatile 发布；读侧无锁。 */
    private volatile Map<String, NodeState> states = Map.of();

    public ClusterNodeStatusMonitor(
            StringProperties properties,
            ClusterMembers clusterMembers,
            PeerBootstrapSource peerSource,
            MeterRegistry meterRegistry) {
        this.clusterMembers = clusterMembers;
        this.peerSource = peerSource;
        this.probeTimeoutMs = properties.getLongProperty(
                KEY_PROBE_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_PROBE_TIMEOUT_MS, 100L, 60_000L));
        this.scheduler = new AbstractIntervalScheduler(
                "artemis-peer-probe-scheduler",
                properties.getLongProperty(
                        KEY_PROBE_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_PROBE_INTERVAL_MS, 100L, 600_000L)),
                0,
                this::probeRound) {};
        this.meterRegistry = meterRegistry;
        this.probeRounds = Counter.builder("artemis.registry.cluster.probe.rounds")
                .description("Background peer status probe rounds")
                .register(meterRegistry);
        Gauge.builder("artemis.registry.cluster.peers.up", this, ClusterNodeStatusMonitor::upPeerCount)
                .description("Current number of peers probed as UP")
                .register(meterRegistry);
    }

    /** 装配层在集群模式下调用；单节点模式不启动调度，保持零开销。 */
    public void start() {
        scheduler.start();
    }

    @Override
    public NodeState stateOf(String nodeId) {
        return states.getOrDefault(nodeId, NodeState.UNKNOWN);
    }

    private int upPeerCount() {
        int count = 0;
        for (NodeState state : states.values()) {
            if (state == NodeState.UP) {
                count++;
            }
        }
        return count;
    }

    private void countProbeFailure(String peerNodeId) {
        failureCounters
                .computeIfAbsent(
                        peerNodeId,
                        id -> Counter.builder("artemis.registry.cluster.probe.failures")
                                .description("Peer status probe failures")
                                .tag("peer", id)
                                .register(meterRegistry))
                .increment();
    }

    private void probeRound() {
        probeRounds.increment();
        String selfNodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse(null);
        List<ClusterMember> peers = new ArrayList<>();
        for (ClusterMember member : clusterMembers.members()) {
            if (!member.nodeId().equals(selfNodeId)) {
                peers.add(member);
            }
        }
        if (peers.isEmpty()) {
            publish(Map.of());
            return;
        }

        long timeoutMs = probeTimeoutMs.getValue();
        List<Callable<ProbeResult>> tasks = new ArrayList<>(peers.size());
        for (ClusterMember peer : peers) {
            tasks.add(() -> probe(peer, timeoutMs));
        }
        List<Future<ProbeResult>> futures;
        try {
            // 并发探测 + 整轮上限：未在 timeout 内完成的任务被取消
            futures = probeExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 关闭中
            return;
        }

        Map<String, NodeState> next = new LinkedHashMap<>();
        for (int i = 0; i < peers.size(); i++) {
            ClusterMember peer = peers.get(i);
            Future<ProbeResult> future = futures.get(i);
            ProbeResult result;
            if (future.isCancelled()) {
                result = new ProbeResult(NodeState.UNKNOWN, true);
                log.debug("peer {} probe timed out after {}ms", peer.nodeId(), timeoutMs);
            } else {
                try {
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = new ProbeResult(NodeState.UNKNOWN, true);
                } catch (ExecutionException e) {
                    result = new ProbeResult(NodeState.UNKNOWN, true);
                    log.debug("peer {} probe failed", peer.nodeId(), e.getCause());
                }
            }
            // 失败只计一次：超时取消时 probe() 内部也会返回 failed，但计数统一在此
            if (result.failed()) {
                countProbeFailure(peer.nodeId());
            }
            next.put(peer.nodeId(), result.state());
        }
        publish(next);
    }

    private ProbeResult probe(ClusterMember peer, long timeoutMs) {
        try {
            NodeStatus status = peerSource.status(peer, timeoutMs);
            if (!peer.nodeId().equals(status.nodeId())) {
                log.warn(
                        "peer {} returned node id {}, but cluster config says {}",
                        peer.nodeId(),
                        status.nodeId(),
                        peer.nodeId());
                return new ProbeResult(NodeState.UNKNOWN, true);
            }
            return new ProbeResult(status.state(), false);
        } catch (RuntimeException e) {
            log.debug("peer {} status probe failed: {}", peer.nodeId(), e.toString());
            return new ProbeResult(NodeState.UNKNOWN, true);
        }
    }

    private record ProbeResult(NodeState state, boolean failed) {}

    private void publish(Map<String, NodeState> next) {
        Map<String, NodeState> previous = states;
        states = Map.copyOf(next);
        for (Map.Entry<String, NodeState> entry : states.entrySet()) {
            NodeState before = previous.getOrDefault(entry.getKey(), NodeState.UNKNOWN);
            NodeState after = entry.getValue();
            if (before == after) {
                continue;
            }
            if (after == NodeState.UP) {
                log.info("peer {} is UP", entry.getKey());
            } else if (before == NodeState.UP) {
                log.warn("peer {} changed from UP to {}", entry.getKey(), after);
            } else {
                log.info("peer {} is {}", entry.getKey(), after);
            }
        }
    }

    @Override
    public void close() {
        scheduler.close();
        probeExecutor.shutdownNow();
    }
}
