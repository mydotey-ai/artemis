package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.Test;
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
        EjectionRuleGrpcService service = new EjectionRuleGrpcService(dispatcher);

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<EjectionRulesEvent> observer = mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(false);
        when(observer.isCancelled()).thenReturn(false);

        service.watchEjections(EjectionRulesRequest.getDefaultInstance(), observer);

        // 快照推送 requireReady 抛出 → dispatcher 断流注销 + close（流关断）
        verify(observer).onError(any());
        assertThat(dispatcher.size()).isZero();
    }
}
