package org.mydotey.ai.artemis.management.domain;

/**
 * 服务事实摘要（registry 只读查询结果）。
 */
public record ServiceSummary(ServiceKey key, int instanceCount) {}
