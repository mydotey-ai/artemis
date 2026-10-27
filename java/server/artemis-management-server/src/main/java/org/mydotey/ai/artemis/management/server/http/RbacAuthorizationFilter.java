package org.mydotey.ai.artemis.management.server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.AuthenticatedAccount;
import org.mydotey.ai.artemis.management.domain.Permission;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RBAC 判权过滤器（v1.0 phase-2，仅 DB 态装配，注册于认证过滤器之后）：
 * 认证后判权——401（未认证，认证层）与 403（已认证越权）分层；路由逐端点登记，
 * 未登记默认 deny（fail-closed）。无 principal = 认证被显式关闭（eval 语义），放行。
 * console 静态资源与内部 gRPC 端口不经此 filter。
 */
@Slf4j
public final class RbacAuthorizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (!path.startsWith("/admin/v1")) {
            filterChain.doFilter(request, response);
            return;
        }
        AuthenticatedAccount principal =
                (AuthenticatedAccount) request.getAttribute(HttpBasicAuthFilter.AUTH_PRINCIPAL_ATTRIBUTE);
        String method = request.getMethod();
        if (principal == null || AdminRoutePermissions.isSelfPasswordChange(method, path)) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<Permission> required = AdminRoutePermissions.match(method, path);
        if (required.isPresent() && required.get().grantedBy(principal.role())) {
            filterChain.doFilter(request, response);
            return;
        }
        log.info(
                "management access forbidden: username=<{}>, role={}, {} {}",
                principal.username(),
                principal.role(),
                method,
                path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"FORBIDDEN\"}");
    }
}
