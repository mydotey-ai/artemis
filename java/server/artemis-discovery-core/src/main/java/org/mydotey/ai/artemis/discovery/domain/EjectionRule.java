package org.mydotey.ai.artemis.discovery.domain;

/**
 * 摘除规则（management 摘除记录的 discovery 侧投影，跨模块只走 wire）。
 * 按 (namespace, service, instanceId) 匹配所有来源 cluster 的同键实例（D-E5）。
 */
public record EjectionRule(ServiceKey service, String instanceId, String reason) {}
