package org.mydotey.ai.artemis.management.domain;

/**
 * 逻辑实例（静态实例，资产 #6）：管理面维护的不经注册体系实例（第三方/异构服务），
 * 经专属订阅流下发、进 discovery 统一发现视图。唯一键 (namespace, service, instanceId)；
 * 无租约无 TTL——status 为管理面静态事实，分发照原样（客户端自报哲学延续）。
 */
public record LogicalInstance(ServiceKey service, Instance instance) {}
