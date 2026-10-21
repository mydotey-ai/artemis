package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;

/**
 * 复制用例（v0.2）：成员/节点状态自报（phase-1）+ 增量批量入站应用（phase-2）；
 * peer bootstrap（phase-3）在此接口扩展。
 */
public interface ReplicationService {

    NodeStatus nodeStatus();

    List<ClusterMember> members();

    /**
     * 增量复制入站：校验（source 非空、逐变更 instanceId 非空——失败整批拒绝 INVALID_ARGUMENT）
     * 后零事件应用全部变更（防回环）。成功恒 ACK_SUCCESS。
     */
    ResponseStatus replicate(ReplicationBatch batch);
}
