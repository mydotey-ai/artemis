package org.mydotey.ai.artemis.management.server.http;

import java.util.List;
import java.util.Optional;
import org.mydotey.ai.artemis.management.domain.Action;
import org.mydotey.ai.artemis.management.domain.Permission;
import org.mydotey.ai.artemis.management.domain.Resource;
import org.springframework.util.AntPathMatcher;

/**
 * `/admin/v1` 路由 → 权限登记表（v1.0 phase-2 RBAC）：逐端点显式登记（method + AntPath），
 * 未登记路径由 {@link RbacAuthorizationFilter} 默认 deny。自助改密不进矩阵（认证即可）。
 */
final class AdminRoutePermissions {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private record Route(String method, String pattern, Permission permission) {}

    private static final List<Route> ROUTES = List.of(
            new Route("GET", "/admin/v1/services/**", read(Resource.SERVICE)),
            new Route("GET", "/admin/v1/subscriptions", read(Resource.SERVICE)),
            new Route("GET", "/admin/v1/status/**", read(Resource.SERVICE)),
            new Route("GET", "/admin/v1/ejections", read(Resource.EJECTION)),
            new Route("POST", "/admin/v1/ejections", write(Resource.EJECTION)),
            new Route("POST", "/admin/v1/ejections/recover", write(Resource.EJECTION)),
            new Route("GET", "/admin/v1/route-rules/**", read(Resource.ROUTE_RULE)),
            new Route("POST", "/admin/v1/route-rules/**", write(Resource.ROUTE_RULE)),
            new Route("PUT", "/admin/v1/route-rules/**", write(Resource.ROUTE_RULE)),
            new Route("DELETE", "/admin/v1/route-rules/**", write(Resource.ROUTE_RULE)),
            new Route("POST", "/admin/v1/canary/**", write(Resource.ROUTE_RULE)),
            new Route("DELETE", "/admin/v1/canary/**", write(Resource.ROUTE_RULE)),
            new Route("GET", "/admin/v1/logical-instances", read(Resource.LOGICAL_INSTANCE)),
            new Route("PUT", "/admin/v1/logical-instances", write(Resource.LOGICAL_INSTANCE)),
            new Route("DELETE", "/admin/v1/logical-instances", write(Resource.LOGICAL_INSTANCE)),
            new Route("GET", "/admin/v1/audit-logs", read(Resource.AUDIT)),
            new Route("*", "/admin/v1/users/**", new Permission(Resource.USER, Action.MANAGE)),
            new Route("GET", "/admin/v1/roles", new Permission(Resource.USER, Action.MANAGE)));

    private AdminRoutePermissions() {}

    /** 匹配首个登记路由；未登记 = empty（调用方默认 deny）。 */
    static Optional<Permission> match(String method, String path) {
        return ROUTES.stream()
                .filter(route -> ("*".equals(route.method()) || route.method().equals(method))
                        && MATCHER.match(route.pattern(), path))
                .map(Route::permission)
                .findFirst();
    }

    /** 自助改密：任何已认证用户可用（凭证卫生操作，不进权限矩阵）。 */
    static boolean isSelfPasswordChange(String method, String path) {
        return "PUT".equals(method) && "/admin/v1/account/password".equals(path);
    }

    private static Permission read(Resource resource) {
        return new Permission(resource, Action.READ);
    }

    private static Permission write(Resource resource) {
        return new Permission(resource, Action.WRITE);
    }
}
