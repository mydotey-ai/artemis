package org.mydotey.ai.artemis.registry.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;

/** readiness 拦截语义：未 markReady 拒绝（UNAVAILABLE、不进 handler），markReady 后放行委派。 */
class ReadinessInterceptorTest {

    @Test
    void should_reject_before_ready_and_allow_after() {
        ReadinessGate gate = new ReadinessGate();
        ReadinessInterceptor interceptor = new ReadinessInterceptor(gate);
        AtomicBoolean delegated = new AtomicBoolean();
        ServerCallHandler<Object, Object> handler = (call, headers) -> {
            delegated.set(true);
            return new ServerCall.Listener<>() {};
        };

        FakeCall rejected = new FakeCall();
        interceptor.interceptCall(rejected, new Metadata(), handler);
        assertThat(rejected.closed).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(delegated).isFalse();

        gate.markReady();
        FakeCall allowed = new FakeCall();
        interceptor.interceptCall(allowed, new Metadata(), handler);
        assertThat(allowed.closed).isNull();
        assertThat(delegated).isTrue();
    }

    /** 记录 close 状态的假 ServerCall（单测不起真实 rpc）。 */
    private static final class FakeCall extends ServerCall<Object, Object> {

        private Status.Code closed;

        @Override
        public void request(int numMessages) {}

        @Override
        public void sendHeaders(Metadata headers) {}

        @Override
        public void sendMessage(Object message) {}

        @Override
        public void close(Status status, Metadata trailers) {
            this.closed = status.getCode();
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<Object, Object> getMethodDescriptor() {
            throw new UnsupportedOperationException();
        }
    }
}
