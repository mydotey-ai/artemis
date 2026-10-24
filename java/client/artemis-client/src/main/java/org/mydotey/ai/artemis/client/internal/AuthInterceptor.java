package org.mydotey.ai.artemis.client.internal;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.mydotey.scf.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC Basic 认证 interceptor（registry/discovery 传输共用，v0.3 phase-8 自 GrpcRegistryClient 提取）：
 * 凭证为 SCF 配置且**使用时重读**（变更运行时生效）；两端都配置才发认证头，裁决归服务端。
 */
final class AuthInterceptor implements ClientInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);

    private final Property<String, String> username;
    private final Property<String, String> password;

    AuthInterceptor(Property<String, String> username, Property<String, String> password) {
        this.username = username;
        this.password = password;
        // 半配置（只配一项）几乎必是失误，WARN 提示（两端共用，单一告警点）
        if (ClientProperties.isNonBlank(username.getValue()) != ClientProperties.isNonBlank(password.getValue())) {
            LOG.warn("artemis.client.auth.username/password must be set together; authentication header not sent");
        }
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                String usernameValue = username.getValue();
                String passwordValue = password.getValue();
                if (ClientProperties.isNonBlank(usernameValue) && ClientProperties.isNonBlank(passwordValue)) {
                    headers.put(
                            Metadata.Key.of(ClientBasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                            ClientBasicAuth.headerValue(usernameValue, passwordValue));
                }
                super.start(listener, headers);
            }
        };
    }
}
