package org.mydotey.ai.artemis.discovery.server.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoFacade;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoMapper;
import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.Subscription;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 订阅推送出口（外部端口，D-S8/D-S9）：一行委派 application proto 门面；readiness/认证由
 * 拦截器承担。慢消费者（isReady 背压，D-S8）服务端断流 RESOURCE_EXHAUSTED + 注销，消费方
 * 重订阅重新基线（at-most-once 自愈）。推送指标就地定义（active/push/closed，core 不感知）。
 */
@Slf4j
@GrpcService
public final class DiscoverySubscriptionGrpcService
        extends DiscoverySubscriptionServiceGrpc.DiscoverySubscriptionServiceImplBase {

    private final DiscoveryProtoFacade facade;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    private final AtomicLong activeSubscriptions;
    private final io.micrometer.core.instrument.Counter pushes;
    private final io.micrometer.core.instrument.Counter slowConsumerClosures;

    public DiscoverySubscriptionGrpcService(
            DiscoveryProtoFacade facade, io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.facade = facade;
        this.activeSubscriptions =
                meterRegistry.gauge("artemis.discovery.subscription.active", new AtomicLong(), AtomicLong::doubleValue);
        this.pushes = io.micrometer.core.instrument.Counter.builder("artemis.discovery.subscription.push")
                .description("Subscription change events pushed to subscribers")
                .register(meterRegistry);
        this.slowConsumerClosures = io.micrometer.core.instrument.Counter.builder(
                        "artemis.discovery.subscription.closed.slow-consumer")
                .description("Subscriptions closed by server due to slow consumer backpressure")
                .register(meterRegistry);
    }

    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<SubscriptionEvent> responseObserver) {
        ServerCallStreamObserver<SubscriptionEvent> observer =
                (ServerCallStreamObserver<SubscriptionEvent>) responseObserver;
        StreamPushTarget target = new StreamPushTarget(observer);
        Subscription subscription;
        try {
            subscription = facade.subscribe(request, target, RemoteAddressInterceptor.remoteAddress());
        } catch (IllegalArgumentException e) {
            target.release(); // 构造时已计入 active，校验失败路径释放
            observer.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asException());
            return;
        }
        target.boundTo(subscription);
        // 取消可能早于 handler 注册/订阅登记（同 registry ProjectionGrpcService 先例）：补一次清理
        observer.setOnCancelHandler(target::release);
        if (observer.isCancelled()) {
            target.release();
        }
        log.info(
                "subscription opened, services={}, labels={}",
                request.getServicesCount(),
                request.getLabelsMap().keySet());
    }

    /** gRPC 流适配（PushTarget）：closed 终态标记后静默；推送与断流以锁串行化。 */
    private final class StreamPushTarget implements PushTarget {

        private final ServerCallStreamObserver<SubscriptionEvent> observer;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile Subscription subscription; // facade 订阅成功后绑定

        StreamPushTarget(ServerCallStreamObserver<SubscriptionEvent> observer) {
            this.observer = observer;
            activeSubscriptions.incrementAndGet();
        }

        void boundTo(Subscription subscription) {
            this.subscription = subscription;
            if (released.get()) { // 绑定前已被取消：立即注销
                subscription.close();
            }
        }

        @Override
        public synchronized void established() {
            if (released.get()) {
                return;
            }
            if (!observer.isReady()) {
                closeSlowConsumer();
                return;
            }
            send(mapper.toEstablishmentEvent());
        }

        @Override
        public synchronized void accept(String cluster, ServiceChange change) {
            if (released.get()) {
                return;
            }
            if (!observer.isReady()) {
                closeSlowConsumer();
                return;
            }
            send(mapper.toProto(cluster, change));
            pushes.increment();
        }

        /** 慢消费者（outbound 背压，D-S8）：断流防无界缓冲（局限 #13 服务端版），重订阅自愈。 */
        private void closeSlowConsumer() {
            log.warn("subscription stream not ready (slow consumer), closing");
            slowConsumerClosures.increment();
            closeStream(Status.RESOURCE_EXHAUSTED.withDescription("slow consumer"));
        }

        /** 取消/完成/断流三路清理（幂等）：注销登记 + 指标释放。 */
        void release() {
            if (released.compareAndSet(false, true)) {
                activeSubscriptions.decrementAndGet();
                Subscription bound = subscription;
                if (bound != null) {
                    bound.close();
                }
            }
        }

        private void closeStream(Status status) {
            release();
            try {
                observer.onError(status.asException());
            } catch (RuntimeException e) {
                // 流可能已由客户端关闭：忽略
            }
        }

        private void send(SubscriptionEvent event) {
            try {
                observer.onNext(event);
            } catch (RuntimeException e) {
                // 流已断（客户端消失等）：转入清理，后续推送被 released 拦截
                release();
            }
        }
    }
}
