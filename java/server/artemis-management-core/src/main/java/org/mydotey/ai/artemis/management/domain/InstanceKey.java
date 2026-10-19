package org.mydotey.ai.artemis.management.domain;

/**
 * 实例标识：摘除记录的操作目标。
 */
public record InstanceKey(ServiceKey service, String instanceId) {}
