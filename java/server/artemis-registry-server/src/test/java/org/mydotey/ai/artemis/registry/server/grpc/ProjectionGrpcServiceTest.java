package org.mydotey.ai.artemis.registry.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.projection.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;
import org.mydotey.ai.artemis.proto.projection.v1.WatchRequest;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.ProjectionProtoFacade;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriber;

/** 接入层：未就绪时投影 RPC 返回 UNAVAILABLE（未就绪节点不充当数据源）。 */
class ProjectionGrpcServiceTest {

    private final RecordedObserver<BootstrapResponse> bootstrap = new RecordedObserver<>();
    private final RecordedObserver<WatchEvent> watch = new RecordedObserver<>();

    @Test
    void should_reject_bootstrap_with_unavailable_when_not_ready() {
        ProjectionGrpcService service = notReadyService();

        service.bootstrap(BootstrapRequest.getDefaultInstance(), bootstrap);

        assertThat(bootstrap.error).isNotNull();
        assertThat(Status.fromThrowable(bootstrap.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(bootstrap.responses).isEmpty();
    }

    @Test
    void should_reject_watch_with_unavailable_when_not_ready() {
        ProjectionGrpcService service = notReadyService();

        service.watch(WatchRequest.getDefaultInstance(), watch);

        assertThat(watch.error).isNotNull();
        assertThat(Status.fromThrowable(watch.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    private static ProjectionGrpcService notReadyService() {
        InstanceRegistry registry = new InstanceRegistry(TestProperties.empty(), new MutableClock(), change -> {});
        ProjectionProtoFacade facade = new ProjectionProtoFacade(registry, new ProjectionSubscriberRegistry());
        return new ProjectionGrpcService(facade, new ReadinessGate(), TestProperties.empty());
    }

    /** 未就绪路径不应触达订阅注册；实现仅满足构造契约。 */
    private static final class ProjectionSubscriberRegistry
            implements org.mydotey.ai.artemis.registry.domain.ProjectionSubscriptions {

        @Override
        public void subscribe(ProjectionSubscriber subscriber) {}

        @Override
        public void unsubscribe(ProjectionSubscriber subscriber) {}
    }

    private static final class RecordedObserver<T> implements StreamObserver<T> {

        private final List<T> responses = new java.util.ArrayList<>();
        private Throwable error;

        @Override
        public void onNext(T value) {
            responses.add(value);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {}
    }
}
