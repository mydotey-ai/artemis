package org.mydotey.ai.artemis.management.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** RBAC 权限矩阵（v1.0 phase-2）：全笛卡尔积逐一断言，矩阵漂移即测试失败。 */
class RolePermissionMatrixTest {

    private static final Set<Permission> VIEWER_EXPECTED = Set.of(
            new Permission(Resource.SERVICE, Action.READ),
            new Permission(Resource.EJECTION, Action.READ),
            new Permission(Resource.ROUTE_RULE, Action.READ),
            new Permission(Resource.LOGICAL_INSTANCE, Action.READ));

    private static final Set<Permission> OPERATOR_ONLY = Set.of(
            new Permission(Resource.EJECTION, Action.WRITE),
            new Permission(Resource.ROUTE_RULE, Action.WRITE),
            new Permission(Resource.LOGICAL_INSTANCE, Action.WRITE),
            new Permission(Resource.AUDIT, Action.READ));

    @Test
    void should_grant_only_matrix_permissions_when_role_fixed() {
        Set<Permission> adminExpected = new java.util.HashSet<>(VIEWER_EXPECTED);
        adminExpected.addAll(OPERATOR_ONLY);
        adminExpected.add(new Permission(Resource.USER, Action.MANAGE));
        for (Resource resource : Resource.values()) {
            for (Action action : Action.values()) {
                Permission permission = new Permission(resource, action);
                assertThat(permission.grantedBy(Role.ADMIN))
                        .as("ADMIN %s", permission)
                        .isEqualTo(adminExpected.contains(permission));
                assertThat(permission.grantedBy(Role.OPERATOR))
                        .as("OPERATOR %s", permission)
                        .isEqualTo(VIEWER_EXPECTED.contains(permission) || OPERATOR_ONLY.contains(permission));
                assertThat(permission.grantedBy(Role.VIEWER))
                        .as("VIEWER %s", permission)
                        .isEqualTo(VIEWER_EXPECTED.contains(permission));
            }
        }
    }

    @Test
    void should_not_grant_when_role_absent() {
        assertThat(new Permission(Resource.SERVICE, Action.READ).grantedBy(null))
                .isFalse();
    }
}
