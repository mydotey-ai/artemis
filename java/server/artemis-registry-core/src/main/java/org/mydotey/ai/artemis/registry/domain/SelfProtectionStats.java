package org.mydotey.ai.artemis.registry.domain;

/** 自我保护窗口统计快照（phase-4 指标 / phase-5 状态 API 的数据源）。 */
public record SelfProtectionStats(long windowRenewals, long maxCount, boolean protecting) {}
