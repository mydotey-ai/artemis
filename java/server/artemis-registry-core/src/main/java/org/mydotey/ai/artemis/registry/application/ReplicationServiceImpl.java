package org.mydotey.ai.artemis.registry.application;

import java.time.Clock;
import java.util.List;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;

/**
 * 节点状态自报：就绪门映射（未 ready → STARTING，ready → UP），启动时刻取装配层时钟。
 * 单节点模式（无成员身份）node_id 为空。
 */
public final class ReplicationServiceImpl implements ReplicationService {

    private final ClusterMembers clusterMembers;
    private final ReadinessGate readinessGate;
    private final long startedAtMs;

    public ReplicationServiceImpl(ClusterMembers clusterMembers, ReadinessGate readinessGate, Clock clock) {
        this.clusterMembers = clusterMembers;
        this.readinessGate = readinessGate;
        this.startedAtMs = clock.millis();
    }

    @Override
    public NodeStatus nodeStatus() {
        String nodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse("");
        NodeState state = readinessGate.isReady() ? NodeState.UP : NodeState.STARTING;
        return new NodeStatus(nodeId, state, startedAtMs);
    }

    @Override
    public List<ClusterMember> members() {
        return clusterMembers.members();
    }
}
