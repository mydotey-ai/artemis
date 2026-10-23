package org.mydotey.ai.artemis.discovery.domain;

/** 查询视图单元：service 实例集 + 投影边界注入的来源 cluster（ADR-014）；不可变。 */
public record ClusterServiceInstances(String cluster, ServiceInstances instances) {}
