package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import org.mydotey.ai.artemis.registry.domain.ClusterMemberStatus;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;

/**
 * 复制用例（v0.2）：成员/节点状态自报 + 后台探测观测态（phase-1/3）、增量批量入站应用
 * （phase-2）、全量 bootstrap 快照导出（phase-3）。
 */
public interface ReplicationService {

    NodeStatus nodeStatus();

    /** 成员 + 本节点视角观测态（self = 就绪门，peer = 后台探测）。 */
    List<ClusterMemberStatus> clusterNodes();

    /** 全量注册表快照（bootstrap 数据源；空注册表返回空列表）。 */
    List<ServiceInstances> bootstrapSnapshot();

    /**
     * 增量复制入站：校验（source 非空、逐变更 instanceId 非空——失败整批拒绝 INVALID_ARGUMENT）
     * 后零事件应用全部变更（防回环）。成功恒 ACK_SUCCESS。
     */
    ResponseStatus replicate(ReplicationBatch batch);
}
