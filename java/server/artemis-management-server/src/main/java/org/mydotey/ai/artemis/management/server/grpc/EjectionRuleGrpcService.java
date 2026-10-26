package org.mydotey.ai.artemis.management.server.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.ManagementProtoMapper;
import org.mydotey.ai.artemis.management.domain.EjectionAckRegistry;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.management.v1.AckEjectionsRequest;
import org.mydotey.ai.artemis.proto.management.v1.AckEjectionsResponse;
import org.mydotey.ai.artemis.proto.management.v1.AckEjectionsResponseData;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;

/**
 * 接入层：摘除规则订阅流契约绑定（management → discovery，架构 §2.2 通道①）+
 * 生效 ack 上行（通道②水位半边，phase-5）。条件装配（gRPC 端口配置存在）；
 * dispatcher 锁内串行回调，onNext 串行。水位条目随订阅生命周期登记/清除
 * （close 是客户端取消与服务端断流的共同汇入点）。
 */
@Slf4j
public final class EjectionRuleGrpcService extends EjectionRuleServiceGrpc.EjectionRuleServiceImplBase {

    private final EjectionRuleSubscriptions subscriptions;
    private final EjectionAckRegistry acks;
    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    public EjectionRuleGrpcService(EjectionRuleSubscriptions subscriptions, EjectionAckRegistry acks) {
        this.subscriptions = subscriptions;
        this.acks = acks;
    }

    @Override
    public void watchEjections(EjectionRulesRequest request, StreamObserver<EjectionRulesEvent> responseObserver) {
        log.info("ejection rule stream subscribed by '{}'", request.getSubscriberId());
        // 代际令牌先于订阅登记创建：新流迟到注销（同 id 旧流 cancel/close）不误删新条目
        var subscription = acks.register(request.getSubscriberId());
        RuleStreamSubscriber subscriber =
                new RuleStreamSubscriber(request.getSubscriberId(), subscription, responseObserver, mapper, acks);
        ServerCallStreamObserver<EjectionRulesEvent> serverObserver = null;
        if (responseObserver instanceof ServerCallStreamObserver<EjectionRulesEvent> candidate) {
            serverObserver = candidate;
            serverObserver.setOnCancelHandler(() -> {
                subscriptions.unsubscribe(subscriber);
                acks.evict(subscriber.subscriberId(), subscription);
            });
        }
        subscriptions.subscribe(subscriber);
        // 取消可能早于 handler 注册/订阅登记：补一次显式注销，避免悬挂订阅者与滞留水位
        if (serverObserver != null && serverObserver.isCancelled()) {
            subscriptions.unsubscribe(subscriber);
            acks.evict(subscriber.subscriberId(), subscription);
        }
    }

    @Override
    public void ackEjections(AckEjectionsRequest request, StreamObserver<AckEjectionsResponse> responseObserver) {
        acks.record(request.getSubscriberId(), request.getAppliedVersion());
        responseObserver.onNext(AckEjectionsResponse.newBuilder()
                .setStatus(ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS))
                .setData(AckEjectionsResponseData.getDefaultInstance())
                .build());
        responseObserver.onCompleted();
    }

    /** gRPC 流适配：onNext 可能与 close 并发（推送失败断流），锁串行化 + 终态标记；
     * 慢消费者（isReady 背压）抛出由 dispatcher 断流注销（D-E10，模式同订阅推送出口）。 */
    private static final class RuleStreamSubscriber implements EjectionRuleSubscriber {

        private final String subscriberId;
        private final EjectionAckRegistry.Subscription subscription;
        private final ServerCallStreamObserver<EjectionRulesEvent> observer;
        private final ManagementProtoMapper mapper;
        private final EjectionAckRegistry acks;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuleStreamSubscriber(
                String subscriberId,
                EjectionAckRegistry.Subscription subscription,
                StreamObserver<EjectionRulesEvent> observer,
                ManagementProtoMapper mapper,
                EjectionAckRegistry acks) {
            this.subscriberId = subscriberId;
            this.subscription = subscription;
            this.observer = (ServerCallStreamObserver<EjectionRulesEvent>) observer;
            this.mapper = mapper;
            this.acks = acks;
        }

        String subscriberId() {
            return subscriberId;
        }

        @Override
        public synchronized void onSnapshot(List<EjectionRecord> records, long version) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toSnapshotEvent(records, version));
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
        public synchronized void onRecovered(EjectionTarget target, long version) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toRecoveredEvent(target, version));
            }
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                acks.evict(subscriberId, subscription); // 服务端断流汇入点：水位只反映活订阅
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
