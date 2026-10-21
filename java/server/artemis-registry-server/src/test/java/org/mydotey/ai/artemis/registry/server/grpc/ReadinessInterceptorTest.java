package org.mydotey.ai.artemis.registry.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;

/** readiness 拦截语义：未 markReady 拒绝（UNAVAILABLE、不进 handler），markReady 后放行委派。 */
class ReadinessInterceptorTest {

    private static final String NODE_STATUS_METHOD = "artemis.replication.v1.NodeStatusService/GetNodeStatus";
    private static final String REGISTER_METHOD = "artemis.registry.v1.RegistryService/Register";

    @Test
    void should_reject_before_ready_and_allow_after() {
        ReadinessGate gate = new ReadinessGate();
        ReadinessInterceptor interceptor = new ReadinessInterceptor(gate);
        AtomicBoolean delegated = new AtomicBoolean();
        ServerCallHandler<Object, Object> handler = (call, headers) -> {
            delegated.set(true);
            return new ServerCall.Listener<>() {};
        };

        FakeCall rejected = new FakeCall(REGISTER_METHOD);
        interceptor.interceptCall(rejected, new Metadata(), handler);
        assertThat(rejected.closed).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(delegated).isFalse();

        gate.markReady();
        FakeCall allowed = new FakeCall(REGISTER_METHOD);
        interceptor.interceptCall(allowed, new Metadata(), handler);
        assertThat(allowed.closed).isNull();
        assertThat(delegated).isTrue();
    }

    @Test
    void should_allow_node_status_before_ready() {
        ReadinessInterceptor interceptor = new ReadinessInterceptor(new ReadinessGate());
        AtomicBoolean delegated = new AtomicBoolean();
        ServerCallHandler<Object, Object> handler = (call, headers) -> {
            delegated.set(true);
            return new ServerCall.Listener<>() {};
        };

        FakeCall call = new FakeCall(NODE_STATUS_METHOD);
        interceptor.interceptCall(call, new Metadata(), handler);

        assertThat(call.closed).isNull();
        assertThat(delegated).isTrue();
    }

    /** 记录 close 状态的假 ServerCall（单测不起真实 rpc）。 */
    private static final class FakeCall extends ServerCall<Object, Object> {

        private final String fullMethodName;
        private Status.Code closed;

        private FakeCall(String fullMethodName) {
            this.fullMethodName = fullMethodName;
        }

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
            return MethodDescriptor.<Object, Object>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(fullMethodName)
                    .setRequestMarshaller(new NoopMarshaller())
                    .setResponseMarshaller(new NoopMarshaller())
                    .build();
        }
    }

    private static final class NoopMarshaller implements MethodDescriptor.Marshaller<Object> {

        @Override
        public InputStream stream(Object value) {
            return InputStream.nullInputStream();
        }

        @Override
        public Object parse(InputStream stream) {
            return null;
        }
    }
}
