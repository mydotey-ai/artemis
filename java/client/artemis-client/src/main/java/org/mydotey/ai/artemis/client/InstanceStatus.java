package org.mydotey.ai.artemis.client;

/**
 * 实例状态：客户端自报（服务端不做主动探测）。注册侧缺省 UP。
 *
 * <p>发现侧按 wire 原值保留状态名（{@code UNSPECIFIED}/{@code UNKNOWN} 不归一为 UP）——
 * 本地 checksum 与服务端 canonical 规则（statusName 语义名）必须一致，否则对账永久不一致。
 */
public enum InstanceStatus {
    UNSPECIFIED,
    UNKNOWN,
    UP,
    DOWN
}
