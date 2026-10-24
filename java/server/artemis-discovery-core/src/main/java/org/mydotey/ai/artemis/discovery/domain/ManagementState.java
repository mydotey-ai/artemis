package org.mydotey.ai.artemis.discovery.domain;

/**
 * 摘除规则源同步状态（管理面出口，phase-6 D-E9 / phase-7 D-A5）：语义对齐
 * {@link UpstreamState}，另加未配置 management 的 DISABLED（0..1）。
 */
public enum ManagementState {
    /** 未配置 management（0..1），不订阅规则、不过滤。 */
    DISABLED,
    /** 连接与首轮快照进行中（含曾同步后的重连轮次）。 */
    SYNCING,
    /** 已收到快照并应用，正在消费增量。 */
    SYNCED,
    /** 当前未连接（断流重连中或同步器已停机）：规则冻结，最后已知规则继续生效（D-E3）。 */
    DISCONNECTED
}
