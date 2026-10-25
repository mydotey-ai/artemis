package org.mydotey.ai.artemis.management.server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfig;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * management 外部 HTTP 认证过滤器（ADR-009/ADR-021）：Basic 凭证经裁决端口验证——
 * DB 态验账户表（PBKDF2），内存态比对配置凭证；认证后端不可用 fail-closed（503）。
 */
@Slf4j
public final class HttpBasicAuthFilter extends OncePerRequestFilter {

    /** 认证通过后写入 request attribute 的用户名（治理操作 operator 来源，Controller 经 @RequestAttribute 取）。 */
    public static final String AUTH_USER_ATTRIBUTE = "artemis.auth.user";

    private static final Set<String> WHITELIST = Set.of("/error");

    private final ManagementAuthConfig authConfig;
    private final AccountAuthService authService;

    public HttpBasicAuthFilter(ManagementAuthConfig authConfig, AccountAuthService authService) {
        this.authConfig = authConfig;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // servletPath 不含 context-path，避免 server.servlet.context-path 下白名单失效
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (!authConfig.enabled() || WHITELIST.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            BasicAuth.Credential credential = BasicAuth.decode(request.getHeader(BasicAuth.HEADER));
            if (credential != null && authService.verify(credential.username(), credential.password())) {
                request.setAttribute(AUTH_USER_ATTRIBUTE, credential.username());
                filterChain.doFilter(request, response);
                return;
            }
        } catch (AccountAccessException e) {
            // fail-closed：认证后端不可用拒绝请求，凭证不进日志
            log.warn("management authentication backend unavailable: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"UNAVAILABLE\"}");
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"artemis\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
    }
}
