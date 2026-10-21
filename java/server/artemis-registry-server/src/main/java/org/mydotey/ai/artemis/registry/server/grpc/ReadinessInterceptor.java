package org.mydotey.ai.artemis.registry.server.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * readiness 流量拦截（全局 server interceptor）：就绪前逐 rpc 拒绝为 UNAVAILABLE
 * （服务暂不可用，客户端 SDK 重试语义天然覆盖）。gRPC server 生命周期由 starter 管理、
 * 装配顺序不受控，interceptor 覆盖 v0.2 异步 bootstrap 窗口。
 *
 * <p>对外节点状态 API 按方法名豁免（排障/引导信息面，与原产品 status/cluster 端点不门控一致），
 * 使 STARTING 态可观测；认证仍由全局认证拦截器负责。
 */
@GlobalServerInterceptor
public final class ReadinessInterceptor implements ServerInterceptor {

    static final Set<String> EXEMPT_METHODS = Set.of("artemis.replication.v1.NodeStatusService/GetNodeStatus");

    private final ReadinessGate readinessGate;

    public ReadinessInterceptor(ReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
            ServerCall<RequestT, ResponseT> call, Metadata headers, ServerCallHandler<RequestT, ResponseT> next) {
        if (!readinessGate.isReady()
                && !EXEMPT_METHODS.contains(call.getMethodDescriptor().getFullMethodName())) {
            call.close(Status.UNAVAILABLE.withDescription("registry server not ready"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
