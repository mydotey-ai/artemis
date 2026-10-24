package org.mydotey.ai.artemis.management.server.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.ManagementProtoMapper;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;

/**
 * 接入层：摘除规则订阅流契约绑定（management → discovery，架构 §2.2 通道①）。
 * 条件装配（gRPC 端口配置存在）；dispatcher 锁内串行回调，onNext 串行。
 */
@Slf4j
public final class EjectionRuleGrpcService extends EjectionRuleServiceGrpc.EjectionRuleServiceImplBase {

    private final EjectionRuleSubscriptions subscriptions;
    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    public EjectionRuleGrpcService(EjectionRuleSubscriptions subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void watchEjections(EjectionRulesRequest request, StreamObserver<EjectionRulesEvent> responseObserver) {
        log.info("ejection rule stream subscribed by '{}'", request.getSubscriberId());
        RuleStreamSubscriber subscriber = new RuleStreamSubscriber(responseObserver, mapper);
        ServerCallStreamObserver<EjectionRulesEvent> serverObserver = null;
        if (responseObserver instanceof ServerCallStreamObserver<EjectionRulesEvent> candidate) {
            serverObserver = candidate;
            serverObserver.setOnCancelHandler(() -> subscriptions.unsubscribe(subscriber));
        }
        subscriptions.subscribe(subscriber);
        // 取消可能早于 handler 注册/订阅登记：补一次显式注销，避免悬挂订阅者
        if (serverObserver != null && serverObserver.isCancelled()) {
            subscriptions.unsubscribe(subscriber);
        }
    }

    /** gRPC 流适配：onNext 可能与 close 并发（推送失败断流），锁串行化 + 终态标记；
     * 慢消费者（isReady 背压）抛出由 dispatcher 断流注销（D-E10，模式同订阅推送出口）。 */
    private static final class RuleStreamSubscriber implements EjectionRuleSubscriber {

        private final ServerCallStreamObserver<EjectionRulesEvent> observer;
        private final ManagementProtoMapper mapper;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuleStreamSubscriber(StreamObserver<EjectionRulesEvent> observer, ManagementProtoMapper mapper) {
            this.observer = (ServerCallStreamObserver<EjectionRulesEvent>) observer;
            this.mapper = mapper;
        }

        @Override
        public synchronized void onSnapshot(List<EjectionRecord> records) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toSnapshotEvent(records));
            }
        }

        @Override
        public synchronized void onEjected(EjectionRecord record) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toEjectedEvent(record));
            }
        }

        @Override
        public synchronized void onRecovered(InstanceKey instance) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toRecoveredEvent(instance));
            }
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                observer.onError(io.grpc.Status.UNAVAILABLE
                        .withDescription("ejection rule stream closed by server")
                        .asException());
            }
        }

        /** 背压满 = 慢消费者 → 抛出（dispatcher 断流注销；消费方重连快照自愈，D-E10）。 */
        private void requireReady() {
            if (!observer.isReady()) {
                throw new IllegalStateException("ejection rule stream consumer not ready (backpressure)");
            }
        }
    }
}
