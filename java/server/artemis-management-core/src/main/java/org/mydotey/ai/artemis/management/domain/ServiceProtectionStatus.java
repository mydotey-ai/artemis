package org.mydotey.ai.artemis.management.domain;

/**
 * 单个服务的自我保护状态（registry 保护态查询透传）。
 *
 * @param nodeFallback 服务级未启用，清理判定取节点级状态
 * @param protecting   服务级显式状态（nodeFallback=true 时实际判定看节点级）
 */
public record ServiceProtectionStatus(
        ServiceKey service,
        long windowRenewals,
        long maxCount,
        boolean nodeFallback,
        boolean protecting,
        long protectingForMs) {}
