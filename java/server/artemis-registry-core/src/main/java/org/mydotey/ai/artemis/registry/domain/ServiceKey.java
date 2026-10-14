package org.mydotey.ai.artemis.registry.domain;

/** 服务标识：namespace 多租户隔离 + service 名。 */
public record ServiceKey(String namespace, String service) {}
