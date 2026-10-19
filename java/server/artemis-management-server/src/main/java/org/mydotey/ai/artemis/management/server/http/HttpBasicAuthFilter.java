package org.mydotey.ai.artemis.management.server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfig;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * management 外部 HTTP 认证过滤器（ADR-009）：Basic 凭证，与 registry 使用同一编码/比较实现。
 */
public final class HttpBasicAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of("/error");

    private final ManagementAuthConfig auth;
    private final String expected;

    public HttpBasicAuthFilter(ManagementAuthConfig auth) {
        this.auth = auth;
        this.expected = BasicAuth.headerValue(auth.username(), auth.password());
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
        if (BasicAuth.matches(request.getHeader(BasicAuth.HEADER), expected)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"artemis\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
    }
}
