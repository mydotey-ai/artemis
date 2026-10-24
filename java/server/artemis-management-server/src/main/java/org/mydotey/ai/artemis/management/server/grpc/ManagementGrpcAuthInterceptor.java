package org.mydotey.ai.artemis.management.server.grpc;

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
 * 内部 gRPC 端口认证拦截器（模式同 registry 内部 peer 端口，D-E11）：默认关闭，启用须
 * 显式配置凭证；缺失/错误统一 UNAUTHENTICATED；健康探针放行。与 registry/discovery
 * 拦截器同构复制（第四处），上移 defer 至 v0.4 批次。
 */
@GlobalServerInterceptor
public final class ManagementGrpcAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private static final Set<String> PUBLIC_SERVICES = Set.of("grpc.health.v1.Health");

    private final BooleanSupplier enabled;
    private final Supplier<String> expected;

    public ManagementGrpcAuthInterceptor(BooleanSupplier enabled, Supplier<String> expected) {
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
