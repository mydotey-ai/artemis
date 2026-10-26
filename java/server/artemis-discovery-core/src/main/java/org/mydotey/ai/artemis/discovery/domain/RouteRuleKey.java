package org.mydotey.ai.artemis.discovery.domain;

/** 路由规则定位键（(namespace, service) + name，与 management 资源键同构）。 */
public record RouteRuleKey(ServiceKey service, String name) {}
