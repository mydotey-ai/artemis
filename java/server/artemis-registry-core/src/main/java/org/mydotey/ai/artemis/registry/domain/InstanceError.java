package org.mydotey.ai.artemis.registry.domain;

/** 实例级错误：批量操作部分失败明细。 */
public record InstanceError(InstanceKey instance, ErrorInfo error) {}
