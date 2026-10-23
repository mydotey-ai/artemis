package org.mydotey.ai.artemis.discovery.domain;

/** 服务标识：namespace 多租户隔离 + service 名（cluster 是投影边界注入的外禀维度，ADR-014）。 */
public record ServiceKey(String namespace, String service) {}
