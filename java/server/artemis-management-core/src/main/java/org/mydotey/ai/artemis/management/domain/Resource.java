package org.mydotey.ai.artemis.management.domain;

/** 权限资源词汇表（v1.0 phase-2）：§5.2 治理资源归并 + 审计/账户；release/canary 归 route-rule。 */
public enum Resource {
    SERVICE,
    EJECTION,
    ROUTE_RULE,
    LOGICAL_INSTANCE,
    AUDIT,
    USER
}
