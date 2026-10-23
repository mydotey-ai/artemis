package org.mydotey.ai.artemis.discovery.domain;

/** 实例变更事件：DELETE 时 instance 至少携带 instanceId，其余字段无意义（契约语义）。 */
public record InstanceChange(Instance instance, ChangeType type) {}
