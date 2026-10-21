package org.mydotey.ai.artemis.registry.domain;

/**
 * 单个服务的自我保护状态快照（ADR-017）。
 *
 * @param service           服务标识
 * @param windowRenewals    当前窗口续约量（查询时只读求和）
 * @param maxCount          历史峰值（可能已按 maxCountResetMs 或窗口调参回落）
 * @param nodeFallback      服务级未启用（无本地窗口或峰值未达 minCount）：清理判定取节点级状态
 * @param protecting        服务级显式状态；nodeFallback=true 时实际保护判定看 node.protecting
 * @param protectingForMs   服务级保护态已持续时长（单调钟计算；NORMAL 为 0）
 */
public record ServiceProtectionStatus(
        ServiceKey service,
        long windowRenewals,
        long maxCount,
        boolean nodeFallback,
        boolean protecting,
        long protectingForMs) {}
