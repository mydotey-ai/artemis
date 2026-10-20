package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;

/**
 * 复制用例（v0.2）：本 phase 只提供成员/节点状态自报；增量扇出（phase-2）
 * 与 peer bootstrap（phase-3）在此接口扩展。
 */
public interface ReplicationService {

    NodeStatus nodeStatus();

    List<ClusterMember> members();
}
