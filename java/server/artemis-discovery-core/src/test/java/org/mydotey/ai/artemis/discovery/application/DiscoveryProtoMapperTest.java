package org.mydotey.ai.artemis.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.QueryResponse;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeBatch;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;

class DiscoveryProtoMapperTest {

    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    @Test
    void should_map_query_request_and_response() {
        QueryRequest request = QueryRequest.newBuilder()
                .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                        .setNamespace("default")
                        .setService("order"))
                .putAllLabels(Map.of("group", "a"))
                .build();
        org.mydotey.ai.artemis.discovery.domain.QueryRequest domain = mapper.toDomain(request);
        assertThat(domain.services()).containsExactly(new ServiceKey("default", "order"));
        assertThat(domain.labels()).containsEntry("group", "a");

        QueryResponse response = new QueryResponse(List.of(new ClusterServiceInstances(
                "cluster-a",
                new ServiceInstances(
                        new ServiceKey("default", "order"),
                        List.of(new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("k", "v")))))));
        org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse proto = mapper.toSuccessProto(response);
        assertThat(proto.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(proto.getData().getServicesCount()).isEqualTo(1);
        assertThat(proto.getData().getServices(0).getCluster()).isEqualTo("cluster-a");
        assertThat(proto.getData().getServices(0).getInstances().getInstances(0).getInstanceId())
                .isEqualTo("i1");

        org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse error =
                mapper.toErrorProto(ErrorCodes.INVALID_ARGUMENT, "blank key");
        assertThat(error.getStatus().getAck()).isEqualTo(Ack.ACK_FAIL);
        assertThat(error.getStatus().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void should_map_change_batch_with_unknown_sentinels() {
        org.mydotey.ai.artemis.proto.projection.v1.ServiceChange service =
                org.mydotey.ai.artemis.proto.projection.v1.ServiceChange.newBuilder()
                        .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                .setNamespace("default")
                                .setService("order"))
                        .addChanges(org.mydotey.ai.artemis.proto.projection.v1.InstanceChange.newBuilder()
                                .setInstance(org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                                        .setInstanceId("i1"))
                                .setType(org.mydotey.ai.artemis.proto.projection.v1.ChangeType.CHANGE_TYPE_DELETE))
                        .build();
        List<ServiceChange> domain =
                mapper.toDomain(ChangeBatch.newBuilder().addServices(service).build());
        assertThat(domain).hasSize(1);
        assertThat(domain.get(0).changes().get(0).type()).isEqualTo(ChangeType.DELETE);
        assertThat(domain.get(0).changes().get(0).instance().instanceId()).isEqualTo("i1");

        // 未知 ChangeType / InstanceStatus 收敛 UNKNOWN 哨兵
        assertThat(mapper.toDomain(org.mydotey.ai.artemis.proto.projection.v1.ChangeType.UNRECOGNIZED))
                .isEqualTo(ChangeType.UNKNOWN);
        assertThat(mapper.toDomain(org.mydotey.ai.artemis.proto.projection.v1.ChangeType.CHANGE_TYPE_UNKNOWN))
                .isEqualTo(ChangeType.UNKNOWN);
        assertThat(mapper.toDomain(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.UNRECOGNIZED))
                .isEqualTo(InstanceStatus.UNKNOWN);
    }

    @Test
    void should_detect_establishment_batch() {
        assertThat(mapper.isEstablishment(WatchEvent.newBuilder()
                        .setChanges(ChangeBatch.newBuilder())
                        .build()))
                .isTrue();
        assertThat(mapper.isEstablishment(WatchEvent.newBuilder()
                        .setChanges(ChangeBatch.newBuilder()
                                .addServices(org.mydotey.ai.artemis.proto.projection.v1.ServiceChange.newBuilder()
                                        .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                                .setService("order")))
                                .build())
                        .build()))
                .isFalse();
    }
}
