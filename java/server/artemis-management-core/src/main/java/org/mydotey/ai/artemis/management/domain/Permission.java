package org.mydotey.ai.artemis.management.domain;

/** 权限点（资源 × 动作）；是否被角色授予由 {@link Role} 的常量矩阵判定。 */
public record Permission(Resource resource, Action action) {

    public boolean grantedBy(Role role) {
        return role != null && role.permissions().contains(this);
    }
}
