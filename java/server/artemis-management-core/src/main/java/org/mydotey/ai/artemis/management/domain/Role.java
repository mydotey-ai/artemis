package org.mydotey.ai.artemis.management.domain;

import java.util.HashSet;
import java.util.Set;

/**
 * 预置角色（v1.0 phase-2 RBAC）：角色→权限矩阵为代码常量（产品语义），DB 只持久化
 * 账户→角色映射。矩阵演进 = 代码变更，无数据迁移；自定义角色出现真实需求时再评估。
 */
public enum Role {
    /** 全权：治理读写 + 审计 + 账户管理。 */
    ADMIN,
    /** 治理面操作员：全部读视图 + 治理写 + 审计读，无账户管理。 */
    OPERATOR,
    /** 只读：服务/摘除/路由/逻辑实例读视图，无审计、无写。 */
    VIEWER;

    private static final Set<Permission> VIEWER_PERMISSIONS = Set.of(
            new Permission(Resource.SERVICE, Action.READ),
            new Permission(Resource.EJECTION, Action.READ),
            new Permission(Resource.ROUTE_RULE, Action.READ),
            new Permission(Resource.LOGICAL_INSTANCE, Action.READ));

    private static final Set<Permission> OPERATOR_PERMISSIONS = union(
            VIEWER_PERMISSIONS,
            new Permission(Resource.EJECTION, Action.WRITE),
            new Permission(Resource.ROUTE_RULE, Action.WRITE),
            new Permission(Resource.LOGICAL_INSTANCE, Action.WRITE),
            new Permission(Resource.AUDIT, Action.READ));

    private static final Set<Permission> ADMIN_PERMISSIONS =
            union(OPERATOR_PERMISSIONS, new Permission(Resource.USER, Action.MANAGE));

    public Set<Permission> permissions() {
        return switch (this) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case OPERATOR -> OPERATOR_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
        };
    }

    private static Set<Permission> union(Set<Permission> base, Permission... extra) {
        Set<Permission> all = new HashSet<>(base);
        all.addAll(Set.of(extra));
        return Set.copyOf(all);
    }
}
