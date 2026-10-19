package org.mydotey.ai.artemis.management.domain;

/**
 * registry 自我保护统计（状态 API 透传）。
 */
public record SelfProtectionStats(long windowRenewals, long maxCount, boolean protecting) {}
