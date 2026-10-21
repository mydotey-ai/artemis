package org.mydotey.ai.artemis.registry.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;
import org.mydotey.ai.artemis.registry.application.ReplicationService;
import org.mydotey.ai.artemis.registry.domain.ClusterMemberStatus;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 内部复制接入层：未就绪拒绝 Replicate/Bootstrap；就绪后 bootstrap 分片流正常结束。 */
class ReplicationGrpcServiceTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    @Test
    void should_reject_replicate_with_unavailable_when_not_ready() {
        ReplicationGrpcService service = new ReplicationGrpcService(
                new ReplicationProtoFacade(fakeService(List.of())), new ReadinessGate(), TestProperties.empty());
        RecordingObserver<ReplicateResponse> observer = new RecordingObserver<>();

        service.replicate(ReplicateRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isNotNull();
        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(observer.values).isEmpty();
    }

    @Test
    void should_reject_bootstrap_with_unavailable_when_not_ready() {
        ReplicationGrpcService service = new ReplicationGrpcService(
                new ReplicationProtoFacade(fakeService(List.of())), new ReadinessGate(), TestProperties.empty());
        RecordingObserver<BootstrapResponse> observer = new RecordingObserver<>();

        service.bootstrap(BootstrapRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isNotNull();
        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void should_stream_full_snapshot_when_ready() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        ReplicationGrpcService service = new ReplicationGrpcService(
                new ReplicationProtoFacade(fakeService(List.of(
                        new ServiceInstances(KEY, List.of(instance("i1"))),
                        new ServiceInstances(new ServiceKey("default", "pay-service"), List.of(instance("p1")))))),
                gate,
                TestProperties.empty());
        RecordingObserver<BootstrapResponse> observer = new RecordingObserver<>();

        service.bootstrap(BootstrapRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).hasSize(1);
        assertThat(observer.values.get(0).getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(observer.values.get(0).getData().getServicesCount()).isEqualTo(2);
    }

    @Test
    void should_split_bootstrap_into_multiple_shards_when_budget_exceeded() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        String blob = "x".repeat(40_000);
        ReplicationGrpcService service = new ReplicationGrpcService(
                new ReplicationProtoFacade(fakeService(List.of(
                        new ServiceInstances(KEY, List.of(instance("i1", blob))),
                        new ServiceInstances(
                                new ServiceKey("default", "pay-service"), List.of(instance("p1", blob)))))),
                gate,
                TestProperties.of(Map.of(ReplicationGrpcService.KEY_SHARD_BYTES, "65536"))
                        .properties());
        RecordingObserver<BootstrapResponse> observer = new RecordingObserver<>();

        service.bootstrap(BootstrapRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).hasSize(2); // 每个 service 约 40KB，64KiB 预算放不下两个
        assertThat(observer.values)
                .allSatisfy(response ->
                        assertThat(response.getData().getServicesCount()).isEqualTo(1));
    }

    @Test
    void should_complete_without_shards_when_registry_empty() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        ReplicationGrpcService service = new ReplicationGrpcService(
                new ReplicationProtoFacade(fakeService(List.of())), gate, TestProperties.empty());
        RecordingObserver<BootstrapResponse> observer = new RecordingObserver<>();

        service.bootstrap(BootstrapRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).isEmpty();
    }

    private static ReplicationService fakeService(List<ServiceInstances> snapshot) {
        return new ReplicationService() {
            @Override
            public NodeStatus nodeStatus() {
                return new NodeStatus("node-a", NodeState.UP, 0);
            }

            @Override
            public List<ClusterMemberStatus> clusterNodes() {
                return List.of();
            }

            @Override
            public List<ServiceInstances> bootstrapSnapshot() {
                return snapshot;
            }

            @Override
            public ResponseStatus replicate(ReplicationBatch batch) {
                return new ResponseStatus(org.mydotey.ai.artemis.registry.domain.Ack.SUCCESS, null);
            }
        };
    }

    private static Instance instance(String instanceId) {
        return instance(instanceId, null);
    }

    private static Instance instance(String instanceId, String metadataBlob) {
        return new Instance(
                instanceId,
                "10.0.0.1",
                8080,
                InstanceStatus.UP,
                100,
                metadataBlob == null ? java.util.Map.of() : java.util.Map.of("blob", metadataBlob));
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {

        private final List<T> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
