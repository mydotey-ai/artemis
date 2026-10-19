package org.mydotey.ai.artemis.management.domain;

/**
 * 租约明细（状态 API 透传）。
 */
public record LeaseDetail(ServiceKey service, String instanceId, long ageMs, long remainingTtlMs) {}
