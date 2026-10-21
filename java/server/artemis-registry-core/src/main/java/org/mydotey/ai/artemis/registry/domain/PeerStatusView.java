package org.mydotey.ai.artemis.registry.domain;

/**
 * 端口：peer 状态视图（后台探测结果，只读）。
 * 无记录（尚未探测/本轮失败）返回 {@link NodeState#UNKNOWN}。
 */
public interface PeerStatusView {

    NodeState stateOf(String nodeId);
}
