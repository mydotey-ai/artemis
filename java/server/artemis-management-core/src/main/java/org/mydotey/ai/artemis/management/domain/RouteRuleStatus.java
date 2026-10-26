package org.mydotey.ai.artemis.management.domain;

/** 路由规则状态（继承原产品 active/inactive：停用不删除）；INACTIVE = discovery 不应用（执行语义 v0.5 phase-3）。 */
public enum RouteRuleStatus {
    ACTIVE,
    INACTIVE
}
