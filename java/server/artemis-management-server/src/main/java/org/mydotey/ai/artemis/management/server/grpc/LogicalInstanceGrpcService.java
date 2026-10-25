package org.mydotey.ai.artemis.management.server.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.ManagementProtoMapper;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriber;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriptions;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstanceServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesRequest;

/**
 * 接入层：逻辑实例订阅流契约绑定（management → discovery，v0.4 phase-4，架构 §2.2 通道①
 * 同内部端口）。条件装配（gRPC 端口配置存在）；dispatcher 锁内串行回调，onNext 串行。
 */
@Slf4j
public final class LogicalInstanceGrpcService extends LogicalInstanceServiceGrpc.LogicalInstanceServiceImplBase {

    private final LogicalInstanceSubscriptions subscriptions;
    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    public LogicalInstanceGrpcService(LogicalInstanceSubscriptions subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void watchLogicalInstances(
            LogicalInstancesRequest request, StreamObserver<LogicalInstancesEvent> responseObserver) {
        log.info("logical instance stream subscribed by '{}'", request.getSubscriberId());
        LogicalStreamSubscriber subscriber = new LogicalStreamSubscriber(responseObserver, mapper);
        ServerCallStreamObserver<LogicalInstancesEvent> serverObserver = null;
        if (responseObserver instanceof ServerCallStreamObserver<LogicalInstancesEvent> candidate) {
            serverObserver = candidate;
            serverObserver.setOnCancelHandler(() -> subscriptions.unsubscribe(subscriber));
        }
        subscriptions.subscribe(subscriber);
        // 取消可能早于 handler 注册/订阅登记：补一次显式注销，避免悬挂订阅者
        if (serverObserver != null && serverObserver.isCancelled()) {
            subscriptions.unsubscribe(subscriber);
        }
    }

    /** gRPC 流适配（模式同 EjectionRuleGrpcService.RuleStreamSubscriber）：锁串行化 + 终态标记；
     * 慢消费者（isReady 背压）抛出由 dispatcher 断流注销（at-most-once，重连快照自愈）。 */
    private static final class LogicalStreamSubscriber implements LogicalInstanceSubscriber {

        private final ServerCallStreamObserver<LogicalInstancesEvent> observer;
        private final ManagementProtoMapper mapper;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LogicalStreamSubscriber(StreamObserver<LogicalInstancesEvent> observer, ManagementProtoMapper mapper) {
            this.observer = (ServerCallStreamObserver<LogicalInstancesEvent>) observer;
            this.mapper = mapper;
        }

        @Override
        public synchronized void onSnapshot(List<LogicalInstance> instances) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toLogicalSnapshotEvent(instances));
            }
        }

        @Override
        public synchronized void onUpserted(LogicalInstance instance) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toLogicalUpsertedEvent(instance));
            }
        }

        @Override
        public synchronized void onDeleted(ServiceKey service, String instanceId) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toLogicalDeletedEvent(service, instanceId));
            }
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                observer.onError(io.grpc.Status.UNAVAILABLE
                        .withDescription("logical instance stream closed by server")
                        .asException());
            }
        }

        /** 背压满 = 慢消费者 → 抛出（dispatcher 断流注销；消费方重连快照自愈）。 */
        private void requireReady() {
            if (!observer.isReady()) {
                throw new IllegalStateException("logical instance stream consumer not ready (backpressure)");
            }
        }
    }
}
