package org.mydotey.ai.artemis.registry.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfigs;

@SuppressWarnings("unchecked")
class GrpcAuthInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final AuthConfig auth = AuthConfigs.enabled("artemis", "secret");
    private final ServerCall<Object, Object> call = mock(ServerCall.class);
    private final ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
    private final ServerCall.Listener<Object> listener = mock(ServerCall.Listener.class);
    private final AtomicReference<Status> closedWith = new AtomicReference<>();

    {
        when(next.startCall(any(), any())).thenReturn(listener);
        doAnswer(invocation -> {
                    closedWith.set(invocation.getArgument(0));
                    return null;
                })
                .when(call)
                .close(any(), any());
    }

    @Test
    void should_reject_grpc_when_credentials_missing() {
        new GrpcAuthInterceptor(auth).interceptCall(call, new Metadata(), next);

        assertThat(closedWith.get()).isNotNull();
        assertThat(closedWith.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void should_reject_grpc_when_credentials_wrong() {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, BasicAuth.headerValue("artemis", "wrong"));

        new GrpcAuthInterceptor(auth).interceptCall(call, headers, next);

        assertThat(closedWith.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void should_allow_grpc_when_credentials_valid() {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, BasicAuth.headerValue("artemis", "secret"));

        assertThat(new GrpcAuthInterceptor(auth).interceptCall(call, headers, next))
                .isSameAs(listener);
        verify(next).startCall(call, headers);
    }

    @Test
    void should_allow_health_service_without_credentials() {
        MethodDescriptor<Object, Object> health = mock(MethodDescriptor.class);
        when(health.getServiceName()).thenReturn("grpc.health.v1.Health");
        when(call.getMethodDescriptor()).thenReturn(health);

        assertThat(new GrpcAuthInterceptor(auth).interceptCall(call, new Metadata(), next))
                .isSameAs(listener);
    }

    @Test
    void should_skip_grpc_auth_when_disabled() {
        AuthConfig disabled = AuthConfigs.disabled();

        assertThat(new GrpcAuthInterceptor(disabled).interceptCall(call, new Metadata(), next))
                .isSameAs(listener);
    }
}
