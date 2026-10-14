package org.mydotey.ai.artemis.registry.domain;

/** 实例标识：定位单个实例（deregister/update 的操作目标）。 */
public record InstanceKey(ServiceKey service, String instanceId) {}
