package org.mydotey.ai.artemis.registry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMemberStatus;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.ErrorInfo;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerStatusView;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;

/**
 * 节点状态自报：就绪门映射（未 ready → STARTING，ready → UP，优雅停机标记 → DOWN），
 * 启动时刻取装配层时钟。单节点模式（无成员身份）node_id 为空。
 * 入站复制：校验后零事件应用（指标就地定义，guidelines §1）。
 */
public final class ReplicationServiceImpl implements ReplicationService {

    private final ClusterMembers clusterMembers;
    private final ReadinessGate readinessGate;
    private final PeerStatusView peerStatusView;
    private final InstanceRegistry registry;
    private final long startedAtMs;
    private final Counter batchesReceived;
    private final Counter changesApplied;

    public ReplicationServiceImpl(
            ClusterMembers clusterMembers,
            ReadinessGate readinessGate,
            PeerStatusView peerStatusView,
            InstanceRegistry registry,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.clusterMembers = clusterMembers;
        this.readinessGate = readinessGate;
        this.peerStatusView = peerStatusView;
        this.registry = registry;
        this.startedAtMs = clock.millis();
        this.batchesReceived = Counter.builder("artemis.registry.replication.batches.received")
                .description("Incremental replication batches received from peers")
                .register(meterRegistry);
        this.changesApplied = Counter.builder("artemis.registry.replication.applied.changes")
                .description("Instance changes applied from peer replication")
                .register(meterRegistry);
    }

    @Override
    public NodeStatus nodeStatus() {
        String nodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse("");
        return new NodeStatus(nodeId, selfState(), startedAtMs);
    }

    @Override
    public List<ClusterMemberStatus> clusterNodes() {
        String selfNodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse(null);
        NodeState selfState = selfState();
        List<ClusterMemberStatus> nodes =
                new ArrayList<>(clusterMembers.members().size());
        for (ClusterMember member : clusterMembers.members()) {
            NodeState state = member.nodeId().equals(selfNodeId) ? selfState : peerStatusView.stateOf(member.nodeId());
            nodes.add(new ClusterMemberStatus(member, state));
        }
        return List.copyOf(nodes);
    }

    @Override
    public List<ServiceInstances> bootstrapSnapshot() {
        return registry.snapshots();
    }

    @Override
    public ResponseStatus replicate(ReplicationBatch batch) {
        String error = validate(batch);
        if (error != null) {
            return new ResponseStatus(Ack.FAIL, new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, error));
        }
        batchesReceived.increment();
        changesApplied.increment(registry.applyReplicated(batch.changes()));
        return new ResponseStatus(Ack.SUCCESS, null);
    }

    /** self 观测态：优雅停机 DOWN 优先（自报下线，peer 探测原样传播），否则就绪门映射。 */
    private NodeState selfState() {
        if (readinessGate.isDown()) {
            return NodeState.DOWN;
        }
        return readinessGate.isReady() ? NodeState.UP : NodeState.STARTING;
    }

    /** 信任边界一次校验：source、service key 与逐变更 instanceId 非空、变更类型可识别（与外部入口对齐）。 */
    private static String validate(ReplicationBatch batch) {
        if (batch.sourceNodeId() == null || batch.sourceNodeId().isBlank()) {
            return "batch.sourceNodeId is blank";
        }
        for (InstanceChange change : batch.changes()) {
            if (change.type() == null) {
                return "change type is null";
            }
            if (change.key() == null
                    || change.key().namespace() == null
                    || change.key().namespace().isBlank()
                    || change.key().service() == null
                    || change.key().service().isBlank()) {
                return "change service key is blank";
            }
            if (change.instance() == null
                    || change.instance().instanceId() == null
                    || change.instance().instanceId().isBlank()) {
                return "change instanceId is blank";
            }
        }
        return null;
    }
}
