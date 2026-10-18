package org.mydotey.ai.artemis.registry.domain;

/** 服务摘要（内部只读查询）：服务标识 + 当前注册实例数。 */
public record ServiceSummary(ServiceKey key, int instanceCount) {}
