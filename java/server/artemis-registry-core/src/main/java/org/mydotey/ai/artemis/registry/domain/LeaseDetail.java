package org.mydotey.ai.artemis.registry.domain;

/**
 * 租约明细（排障用）：age 与剩余 TTL 均在聚合内按单调钟计算，不对外暴露单调时刻。
 * remainingTtlMs 可为负，表示已到期待清理。
 */
public record LeaseDetail(ServiceKey service, String instanceId, long ageMs, long remainingTtlMs) {}
