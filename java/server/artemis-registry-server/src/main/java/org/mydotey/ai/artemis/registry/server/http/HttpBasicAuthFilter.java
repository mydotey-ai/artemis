package org.mydotey.ai.artemis.registry.server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 外部 HTTP 通道认证过滤器（ADR-009）：与 gRPC 共用 Basic 凭证；就绪探针等白名单路径放行。
 */
public final class HttpBasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of("/readyz", "/error");

    private final AuthConfig auth;

    public HttpBasicAuthFilter(AuthConfig auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // servletPath 不含 context-path，避免 server.servlet.context-path 下白名单失效
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (!auth.enabled() || WHITELIST.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 凭证动态：每请求现取，支持运行期轮换；凭证不完整时拒绝
        String expected = BasicAuth.headerValueOrNull(auth.username(), auth.password());
        if (expected != null && BasicAuth.matches(request.getHeader(BasicAuth.HEADER), expected)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"artemis\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
    }
}
