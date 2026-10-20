package org.mydotey.ai.artemis.registry.domain;

/**
 * 本节点状态（自报）。
 *
 * @param nodeId 成员标识；单节点模式为空
 * @param state 就绪态映射（未 ready → STARTING，ready → UP）
 * @param startedAtMs 启动时刻（epoch 毫秒）
 */
public record NodeStatus(String nodeId, NodeState state, long startedAtMs) {

    public NodeStatus {
        if (nodeId == null) {
            throw new IllegalArgumentException("node status node id must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("node status state must not be null");
        }
    }
}
