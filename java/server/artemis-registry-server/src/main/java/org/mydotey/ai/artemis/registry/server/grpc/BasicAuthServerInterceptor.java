package org.mydotey.ai.artemis.registry.server.grpc;

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

/**
 * gRPC Basic 认证拦截器（外部端口与内部端口各持一份凭证）。缺失/错误统一返回
 * UNAUTHENTICATED，不区分用户名是否存在。
 *
 * <p>启用开关与预期凭证均按调用时取值（动态配置）：运行期可轮换 username/password；
 * 启用但凭证不完整（expected 为 null）时拒绝所有调用。
 * 外部端口经 {@link GrpcAuthInterceptor} 注册为全局拦截器；内部端口由装配层显式绑定。
 */
public class BasicAuthServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private final BooleanSupplier enabled;
    private final Supplier<String> expected;
    private final Set<String> publicServices;

    public BasicAuthServerInterceptor(BooleanSupplier enabled, Supplier<String> expected, Set<String> publicServices) {
        this.enabled = enabled;
        this.expected = expected;
        this.publicServices = publicServices == null ? Set.of() : Set.copyOf(publicServices);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        MethodDescriptor<?, ?> method = call.getMethodDescriptor();
        if (method != null && publicServices.contains(method.getServiceName())) {
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
