package org.mydotey.ai.artemis.registry.server;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.mydotey.ai.artemis.common.auth.BasicAuth;

/**
 * 测试 gRPC 客户端凭证设施：默认账户（与生产默认一致），支持无凭证 channel 构造以测拒绝路径。
 */
final class AuthClients {

    static final String USERNAME = "artemis";
    static final String PASSWORD = "artemis";

    private AuthClients() {}

    static ClientInterceptor authenticated() {
        return authenticated(USERNAME, PASSWORD);
    }

    static ClientInterceptor authenticated(String username, String password) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(
                                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                                BasicAuth.headerValue(username, password));
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }
}
