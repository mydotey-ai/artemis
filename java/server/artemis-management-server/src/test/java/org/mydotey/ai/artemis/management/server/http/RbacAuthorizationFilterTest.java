package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.application.AuthenticatedAccount;
import org.mydotey.ai.artemis.management.domain.Action;
import org.mydotey.ai.artemis.management.domain.Permission;
import org.mydotey.ai.artemis.management.domain.Resource;
import org.mydotey.ai.artemis.management.domain.Role;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** RBAC 判权（v1.0 phase-2）：路由登记、默认 deny、角色矩阵 enforcement、认证关闭放行。 */
class RbacAuthorizationFilterTest {

    private final RbacAuthorizationFilter filter = new RbacAuthorizationFilter();

    @Test
    void should_map_admin_v1_routes_to_permissions_when_matched() {
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/services"))
                .hasValue(new Permission(Resource.SERVICE, Action.READ));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/services/default/demo"))
                .hasValue(new Permission(Resource.SERVICE, Action.READ));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/status/leases"))
                .hasValue(new Permission(Resource.SERVICE, Action.READ));
        assertThat(AdminRoutePermissions.match("POST", "/admin/v1/ejections"))
                .hasValue(new Permission(Resource.EJECTION, Action.WRITE));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/ejections"))
                .hasValue(new Permission(Resource.EJECTION, Action.READ));
        assertThat(AdminRoutePermissions.match("POST", "/admin/v1/route-rules/default/demo/rule/release"))
                .hasValue(new Permission(Resource.ROUTE_RULE, Action.WRITE));
        assertThat(AdminRoutePermissions.match("DELETE", "/admin/v1/canary/default/demo"))
                .hasValue(new Permission(Resource.ROUTE_RULE, Action.WRITE));
        assertThat(AdminRoutePermissions.match("PUT", "/admin/v1/logical-instances"))
                .hasValue(new Permission(Resource.LOGICAL_INSTANCE, Action.WRITE));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/audit-logs"))
                .hasValue(new Permission(Resource.AUDIT, Action.READ));
        assertThat(AdminRoutePermissions.match("DELETE", "/admin/v1/users/ops"))
                .hasValue(new Permission(Resource.USER, Action.MANAGE));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/roles"))
                .hasValue(new Permission(Resource.USER, Action.MANAGE));
        assertThat(AdminRoutePermissions.match("GET", "/admin/v1/unknown")).isEmpty();
    }

    @Test
    void should_deny_when_admin_v1_route_not_registered() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/admin/v1/unknown", Role.ADMIN), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_deny_when_role_lacks_permission() throws Exception {
        assertThat(status("POST", "/admin/v1/ejections", Role.VIEWER)).isEqualTo(403);
        assertThat(status("GET", "/admin/v1/users", Role.OPERATOR)).isEqualTo(403);
        assertThat(status("GET", "/admin/v1/audit-logs", Role.VIEWER)).isEqualTo(403);
    }

    @Test
    void should_allow_when_role_granted() throws Exception {
        assertThat(status("GET", "/admin/v1/services", Role.VIEWER)).isEqualTo(200);
        assertThat(status("POST", "/admin/v1/ejections", Role.OPERATOR)).isEqualTo(200);
        assertThat(status("GET", "/admin/v1/audit-logs", Role.OPERATOR)).isEqualTo(200);
        assertThat(status("POST", "/admin/v1/users", Role.ADMIN)).isEqualTo(200);
    }

    @Test
    void should_allow_self_password_change_for_any_role() throws Exception {
        assertThat(status("PUT", "/admin/v1/account/password", Role.VIEWER)).isEqualTo(200);
    }

    @Test
    void should_pass_through_when_path_outside_admin_v1() throws Exception {
        MockHttpServletRequest request = request("GET", "/index.html", Role.VIEWER);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void should_allow_when_authentication_disabled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/users");
        MockFilterChain chain = new MockFilterChain();

        // 无 principal = 认证被显式关闭（eval 语义），判权放行
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private int status(String method, String uri, Role role) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, uri, role), response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest request(String method, String uri, Role role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        Optional.ofNullable(role)
                .ifPresent(r -> request.setAttribute(
                        HttpBasicAuthFilter.AUTH_PRINCIPAL_ATTRIBUTE, new AuthenticatedAccount("someone", r)));
        return request;
    }
}
