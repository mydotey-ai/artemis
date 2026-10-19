package org.mydotey.ai.artemis.management.domain;

/**
 * 实例状态（客户端自报）。proto 的 UNSPECIFIED 语义等同 UP，映射在 application 转换边界完成。
 */
public enum InstanceStatus {
    UP,
    DOWN,
    UNKNOWN
}
