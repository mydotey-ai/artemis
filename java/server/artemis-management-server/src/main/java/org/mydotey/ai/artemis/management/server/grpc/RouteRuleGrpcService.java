package org.mydotey.ai.artemis.management.server.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.ManagementProtoMapper;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.RuleAckRegistry;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.management.v1.AckRouteRulesRequest;
import org.mydotey.ai.artemis.proto.management.v1.AckRouteRulesResponse;
import org.mydotey.ai.artemis.proto.management.v1.AckRouteRulesResponseData;
import org.mydotey.ai.artemis.proto.management.v1.RouteRuleServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.RouteRulesRequest;

/**
 * 接入层：路由规则订阅流契约绑定（management → discovery，架构 §2.2 通道①，phase-2）+
 * 生效 ack 上行（通道②水位半边）。条件装配（gRPC 端口配置存在）；装配模式与生命周期
 * 语义同 {@link EjectionRuleGrpcService}（代际令牌水位登记、慢消费者断流注销）。
 */
@Slf4j
public final class RouteRuleGrpcService extends RouteRuleServiceGrpc.RouteRuleServiceImplBase {

    private final RouteRuleSubscriptions subscriptions;
    private final RuleAckRegistry acks;
    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    public RouteRuleGrpcService(RouteRuleSubscriptions subscriptions, RuleAckRegistry acks) {
        this.subscriptions = subscriptions;
        this.acks = acks;
    }

    @Override
    public void watchRouteRules(RouteRulesRequest request, StreamObserver<RouteRulesEvent> responseObserver) {
        log.info("route rule stream subscribed by '{}'", request.getSubscriberId());
        // 代际令牌先于订阅登记创建：新流迟到注销（同 id 旧流 cancel/close）不误删新条目
        RuleAckRegistry.Subscription subscription = acks.register(request.getSubscriberId());
        RuleStreamSubscriber subscriber =
                new RuleStreamSubscriber(request.getSubscriberId(), subscription, responseObserver, mapper, acks);
        ServerCallStreamObserver<RouteRulesEvent> serverObserver = null;
        if (responseObserver instanceof ServerCallStreamObserver<RouteRulesEvent> candidate) {
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
    public void ackRouteRules(AckRouteRulesRequest request, StreamObserver<AckRouteRulesResponse> responseObserver) {
        acks.record(request.getSubscriberId(), request.getAppliedVersion());
        responseObserver.onNext(AckRouteRulesResponse.newBuilder()
                .setStatus(ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS))
                .setData(AckRouteRulesResponseData.getDefaultInstance())
                .build());
        responseObserver.onCompleted();
    }

    /** gRPC 流适配（onNext 与 close 并发防御、背压断流，同 EjectionRuleGrpcService 模式）。 */
    private static final class RuleStreamSubscriber implements RouteRuleSubscriber {

        private final String subscriberId;
        private final RuleAckRegistry.Subscription subscription;
        private final ServerCallStreamObserver<RouteRulesEvent> observer;
        private final ManagementProtoMapper mapper;
        private final RuleAckRegistry acks;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuleStreamSubscriber(
                String subscriberId,
                RuleAckRegistry.Subscription subscription,
                StreamObserver<RouteRulesEvent> observer,
                ManagementProtoMapper mapper,
                RuleAckRegistry acks) {
            this.subscriberId = subscriberId;
            this.subscription = subscription;
            this.observer = (ServerCallStreamObserver<RouteRulesEvent>) observer;
            this.mapper = mapper;
            this.acks = acks;
        }

        String subscriberId() {
            return subscriberId;
        }

        @Override
        public synchronized void onSnapshot(List<RouteRule> rules, long version) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toRouteRulesSnapshotEvent(rules, version));
            }
        }

        @Override
        public synchronized void onUpserted(RouteRule rule, long version) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toRouteRuleUpsertedEvent(rule, version));
            }
        }

        @Override
        public synchronized void onDeleted(ServiceKey service, String name, long version) {
            requireReady();
            if (!closed.get()) {
                observer.onNext(mapper.toRouteRuleDeletedEvent(service, name, version));
            }
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                acks.evict(subscriberId, subscription); // 服务端断流汇入点：水位只反映活订阅
                observer.onError(io.grpc.Status.UNAVAILABLE
                        .withDescription("route rule stream closed by server")
                        .asException());
            }
        }

        /** 背压满 = 慢消费者 → 抛出（dispatcher 断流注销；消费方重连快照自愈）。 */
        private void requireReady() {
            if (!observer.isReady()) {
                throw new IllegalStateException("route rule stream consumer not ready (backpressure)");
            }
        }
    }
}
