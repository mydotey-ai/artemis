package org.mydotey.ai.artemis.registry.domain;

/**
 * 成员 + 本节点视角的观测态（节点状态 API 的输出单元）。
 *
 * @param member 静态成员信息
 * @param state self = 就绪门；peer = 后台探测结果；未探测/超时/异常 = UNKNOWN
 */
public record ClusterMemberStatus(ClusterMember member, NodeState state) {

    public ClusterMemberStatus {
        if (member == null) {
            throw new IllegalArgumentException("cluster member status member must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("cluster member status state must not be null");
        }
    }
}
