package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.stub.ServerCallStreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.EjectionAckRegistry;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.infrastructure.EjectionRuleDispatcher;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryEjectionStore;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent;
import org.mydotey.ai.artemis.proto.management.v1.EjectionRulesRequest;

/** 规则流 gRPC 适配：慢消费者（isReady 背压）断流注销（D-E10，review FIX-2）。 */
class EjectionRuleGrpcServiceTest {

    @Test
    void should_disconnect_subscriber_when_consumer_not_ready() {
        EjectionStore store = new InMemoryEjectionStore();
        EjectionRuleDispatcher dispatcher = new EjectionRuleDispatcher(store);
        EjectionRuleGrpcService service =
                new EjectionRuleGrpcService(dispatcher, new EjectionAckRegistry(Clock.systemUTC()));

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<EjectionRulesEvent> observer = mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(false);
        when(observer.isCancelled()).thenReturn(false);

        service.watchEjections(EjectionRulesRequest.getDefaultInstance(), observer);

        // 快照推送 requireReady 抛出 → dispatcher 断流注销 + close（流关断）
        verify(observer).onError(any());
        assertThat(dispatcher.size()).isZero();
    }

    @Test
    void should_evict_watermark_when_server_disconnects_subscriber() {
        EjectionStore store = new InMemoryEjectionStore();
        EjectionRuleDispatcher dispatcher = new EjectionRuleDispatcher(store);
        EjectionAckRegistry acks = new EjectionAckRegistry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        EjectionRuleGrpcService service = new EjectionRuleGrpcService(dispatcher, acks);

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<EjectionRulesEvent> observer = mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(false); // 快照推送即失败 → 服务端断流
        when(observer.isCancelled()).thenReturn(false);

        service.watchEjections(
                EjectionRulesRequest.newBuilder().setSubscriberId("sub-1").build(), observer);
        acks.record("sub-1", 1); // 登记后 ack 到位

        // 断流 close 汇入点清除水位：水位只反映活订阅
        assertThat(acks.entries()).isEmpty();
    }
}
