package org.mydotey.ai.artemis.registry.server.grpc;

import java.util.Set;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * 外部端口 gRPC 全局认证拦截器（ADR-009 v0.1 默认开启）：校验 metadata
 * {@code authorization: Basic ...}；缺失/错误统一返回 UNAUTHENTICATED。
 */
@GlobalServerInterceptor
public final class GrpcAuthInterceptor extends BasicAuthServerInterceptor {

    /** 框架健康探针（K8s gRPC probe/Envoy）无凭证，必须放行。 */
    private static final Set<String> PUBLIC_SERVICES = Set.of("grpc.health.v1.Health");

    public GrpcAuthInterceptor(AuthConfig auth) {
        super(auth::enabled, () -> BasicAuth.headerValueOrNull(auth.username(), auth.password()), PUBLIC_SERVICES);
    }
}
