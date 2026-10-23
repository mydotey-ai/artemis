package org.mydotey.ai.artemis.discovery.server.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * readiness 流量拦截（全局 server interceptor）：首轮投影同步完成前逐 rpc 拒绝
 * UNAVAILABLE——空数据不作真相返回（D-D5/资产 #9），客户端重试语义天然覆盖。
 */
@GlobalServerInterceptor
public final class ReadinessInterceptor implements ServerInterceptor {

    private final DiscoveryReadinessGate readinessGate;

    public ReadinessInterceptor(DiscoveryReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
            ServerCall<RequestT, ResponseT> call, Metadata headers, ServerCallHandler<RequestT, ResponseT> next) {
        if (!readinessGate.isReady()) {
            call.close(Status.UNAVAILABLE.withDescription("discovery server not ready"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
