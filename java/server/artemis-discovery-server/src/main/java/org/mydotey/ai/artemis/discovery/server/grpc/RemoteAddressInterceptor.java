package org.mydotey.ai.artemis.discovery.server.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.net.SocketAddress;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * 调用方地址捕获（D-A2）：把 gRPC 传输层远端地址放进 {@link Context}，订阅接入层登记
 * 订阅关系时读取。单账户认证下这是唯一可靠的调用方标识（客户端自报不可校验）。
 */
@GlobalServerInterceptor
public final class RemoteAddressInterceptor implements ServerInterceptor {

    private static final String UNKNOWN = "unknown";
    private static final Context.Key<String> REMOTE_ADDRESS = Context.key("artemis-remote-address");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        SocketAddress address = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        Context context = Context.current().withValue(REMOTE_ADDRESS, address == null ? UNKNOWN : address.toString());
        return Contexts.interceptCall(context, call, headers, next);
    }

    /** 当前调用远端地址；不在 gRPC 调用上下文时为 unknown。 */
    public static String remoteAddress() {
        String address = REMOTE_ADDRESS.get();
        return address == null ? UNKNOWN : address;
    }
}
