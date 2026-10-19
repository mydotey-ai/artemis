package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * gRPC 全局认证拦截器（ADR-009 v0.1 默认开启）：校验 metadata {@code authorization: Basic ...}。
 * 缺失/错误统一返回 UNAUTHENTICATED，不区分用户名是否存在。
 */
@GlobalServerInterceptor
public final class GrpcAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    /** 框架健康探针（K8s gRPC probe/Envoy）无凭证，必须放行。 */
    private static final Set<String> PUBLIC_SERVICES = Set.of("grpc.health.v1.Health");

    private final AuthConfig auth;
    private final String expected;

    public GrpcAuthInterceptor(AuthConfig auth) {
        this.auth = auth;
        this.expected = BasicAuth.headerValue(auth.username(), auth.password());
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        MethodDescriptor<?, ?> method = call.getMethodDescriptor();
        if (method != null && PUBLIC_SERVICES.contains(method.getServiceName())) {
            return next.startCall(call, headers);
        }
        if (auth.enabled() && !BasicAuth.matches(headers.get(AUTHORIZATION), expected)) {
            call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid credentials"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
