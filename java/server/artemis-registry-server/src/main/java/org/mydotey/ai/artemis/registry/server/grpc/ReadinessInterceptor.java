package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * readiness 流量拦截（全局 server interceptor）：就绪前逐 rpc 拒绝为 UNAVAILABLE
 * （服务暂不可用，客户端 SDK 重试语义天然覆盖）。比「控制启动顺序先回放再 bind」可靠——
 * gRPC server 生命周期由 starter 管理、装配顺序不受控，interceptor 同时天然覆盖 v0.2
 * peer 同步窗口。正常路径（bootstrap 先于 server 绑定完成）永远放行。
 */
@GlobalServerInterceptor
public final class ReadinessInterceptor implements ServerInterceptor {

    private final ReadinessGate readinessGate;

    public ReadinessInterceptor(ReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
            ServerCall<RequestT, ResponseT> call, Metadata headers, ServerCallHandler<RequestT, ResponseT> next) {
        if (!readinessGate.isReady()) {
            call.close(Status.UNAVAILABLE.withDescription("registry server not ready"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
