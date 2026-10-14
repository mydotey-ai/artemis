package org.mydotey.ai.artemis.registry.domain;

/** 实例状态（客户端自报，服务端不做主动探测）：语义与契约 common.v1 对齐。 */
public enum InstanceStatus {
    UNSPECIFIED,
    UNKNOWN,
    UP,
    DOWN
}
