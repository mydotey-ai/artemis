package org.mydotey.ai.artemis.registry.domain;

/**
 * 集群成员：静态配置的连接信息（内部 peer 端点 + 对外 SDK/管理面端点）。
 * 观测态不进入本记录，由 {@link ClusterMemberStatus} 携带。
 *
 * @param nodeId 成员标识（配置显式给出，不再按 URL 子串猜测本机）
 * @param peer 内部 peer 端点（复制/成员状态/bootstrap）
 * @param external 对外 gRPC 端点（节点状态 API 向 SDK 宣布的地址）
 */
public record ClusterMember(String nodeId, ClusterEndpoint peer, ClusterEndpoint external) {

    public ClusterMember {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("cluster member node id must not be blank");
        }
        if (peer == null) {
            throw new IllegalArgumentException("cluster member peer endpoint must not be null: " + nodeId);
        }
        if (external == null) {
            throw new IllegalArgumentException("cluster member external endpoint must not be null: " + nodeId);
        }
    }
}
