package org.mydotey.ai.artemis.discovery.server.grpc;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * 外部端口 gRPC 全局认证拦截器（D-D10，模式同 registry 外部端口）：默认关闭，启用须显式
 * 配置凭证；缺失/错误统一 UNAUTHENTICATED。框架健康探针（无凭证）放行。
 * 与 registry-server 认证拦截器同构复制（第三处），上移 defer 至 v0.4 批次（同
 * SpringEnvironmentConfigurationSource 的 D-D9 评估结论）。
 */
@GlobalServerInterceptor
public final class GrpcAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private static final Set<String> PUBLIC_SERVICES = Set.of("grpc.health.v1.Health");

    private final BooleanSupplier enabled;
    private final Supplier<String> expected;

    public GrpcAuthInterceptor(BooleanSupplier enabled, Supplier<String> expected) {
        this.enabled = enabled;
        this.expected = expected;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        MethodDescriptor<?, ?> method = call.getMethodDescriptor();
        if (method != null && PUBLIC_SERVICES.contains(method.getServiceName())) {
            return next.startCall(call, headers);
        }
        if (enabled.getAsBoolean()) {
            String expectedHeader = expected.get();
            if (expectedHeader == null || !BasicAuth.matches(headers.get(AUTHORIZATION), expectedHeader)) {
                call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid credentials"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
        }
        return next.startCall(call, headers);
    }
}
