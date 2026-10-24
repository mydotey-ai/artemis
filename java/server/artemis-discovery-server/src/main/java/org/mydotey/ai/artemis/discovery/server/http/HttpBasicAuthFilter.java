package org.mydotey.ai.artemis.discovery.server.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.server.auth.AuthConfig;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * discovery 外部 HTTP 管理面认证过滤器（D-A7）：复用外部 gRPC 账户（`artemis.discovery.auth.*`，
 * 默认关闭，D-D10）。仅注册到 `/admin/*`，不影响数据面 gRPC。
 *
 * <p>与 registry/management 的 HTTP 过滤器同构复制（第三处）；上移公共实现 defer 至 v0.4
 * 批次（同 GrpcAuthInterceptor 的 D-D9 评估结论），避免本 phase 跨服务重构。
 */
public final class HttpBasicAuthFilter extends OncePerRequestFilter {

    private final AuthConfig auth;

    public HttpBasicAuthFilter(AuthConfig auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!auth.enabled()) {
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
