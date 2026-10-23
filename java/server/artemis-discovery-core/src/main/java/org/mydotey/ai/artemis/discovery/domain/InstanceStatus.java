package org.mydotey.ai.artemis.discovery.domain;

/** 实例状态（客户端自报）；消费侧未知值统一收敛 UNKNOWN（与服务端版本解耦）。 */
public enum InstanceStatus {
    UNSPECIFIED,
    UNKNOWN,
    UP,
    DOWN
}
