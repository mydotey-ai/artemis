package org.mydotey.ai.artemis.registry.domain;

/** 节点状态：自报语义，不做 peer 主动探测。 */
public enum NodeState {
    /** 本地无法识别（wire 未知枚举值的映射目标）。 */
    UNKNOWN,
    /** 启动中，未 ready。 */
    STARTING,
    /** 就绪。 */
    UP,
    /** 显式下线（优雅停机，phase-6）。 */
    DOWN
}
