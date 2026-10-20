package org.mydotey.ai.artemis.registry.domain;

/**
 * 集群成员（静态配置实现的最小连接信息）。对外（SDK/管理面）端点不在此模型，
 * 由节点状态 API 按部署形态确定（phase-3）。
 *
 * @param nodeId 成员标识（配置显式给出，不再按 URL 子串猜测本机）
 * @param peer 内部 peer 端点（复制/成员状态）
 */
public record ClusterMember(String nodeId, ClusterEndpoint peer) {

    public ClusterMember {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("cluster member node id must not be blank");
        }
        if (peer == null) {
            throw new IllegalArgumentException("cluster member peer endpoint must not be null: " + nodeId);
        }
    }
}
