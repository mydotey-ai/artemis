package org.mydotey.ai.artemis.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.ChecksumResponse;
import org.mydotey.ai.artemis.discovery.domain.ClusterChecksums;
import org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.QueryResponse;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceChecksum;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.UpstreamState;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatus;
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

        QueryResponse response = new QueryResponse(
                List.of(new ClusterServiceInstances(
                        "cluster-a",
                        new ServiceInstances(
                                new ServiceKey("default", "order"),
                                List.of(new Instance(
                                        "i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("k", "v")))))),
                List.of());
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

    @Test
    void should_map_upstream_state_and_checksum_roundtrip() {
        // 查询响应新鲜度装饰（D-F4）
        QueryResponse query =
                new QueryResponse(List.of(), List.of(new UpstreamStatus("cluster-a", UpstreamState.DISCONNECTED)));
        org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse queryProto = mapper.toSuccessProto(query);
        assertThat(queryProto.getData().getUpstreamsCount()).isEqualTo(1);
        assertThat(queryProto.getData().getUpstreams(0).getCluster()).isEqualTo("cluster-a");
        assertThat(queryProto.getData().getUpstreams(0).getState())
                .isEqualTo(
                        org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState.UPSTREAM_SYNC_STATE_DISCONNECTED);

        // 三值正向映射均落原值（非 UNSPECIFIED/UNKNOWN 哨兵）
        for (UpstreamState state : UpstreamState.values()) {
            assertThat(mapper.toProto(new UpstreamStatus("c", state)).getState().getNumber())
                    .isGreaterThan(1);
        }

        // 对账请求 → selector（去重 + labels）
        org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest request =
                org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest.newBuilder()
                        .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                .setNamespace("default")
                                .setService("order"))
                        .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                .setNamespace("default")
                                .setService("order"))
                        .putAllLabels(Map.of("group", "a"))
                        .build();
        DiscoverySelector selector = mapper.toSelector(request);
        assertThat(selector.services()).containsExactly(new ServiceKey("default", "order"));
        assertThat(selector.labels()).containsEntry("group", "a");

        // 对账响应：uint32 大值位模式不丢 + 新鲜度
        ChecksumResponse response = new ChecksumResponse(
                List.of(new ClusterChecksums(
                        "cluster-a", List.of(new ServiceChecksum(new ServiceKey("default", "order"), 0x9e3779b9)))),
                List.of(new UpstreamStatus("cluster-a", UpstreamState.SYNCED)));
        org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse checksumProto = mapper.toSuccessProto(response);
        assertThat(checksumProto.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(checksumProto.getData().getClusters(0).getCluster()).isEqualTo("cluster-a");
        assertThat(checksumProto.getData().getClusters(0).getServices(0).getChecksum())
                .isEqualTo(0x9e3779b9);
        assertThat(checksumProto.getData().getUpstreams(0).getState())
                .isEqualTo(org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED);

        org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse checksumError =
                mapper.toErrorChecksumProto(ErrorCodes.INVALID_ARGUMENT, "blank key");
        assertThat(checksumError.getStatus().getAck()).isEqualTo(Ack.ACK_FAIL);
        assertThat(checksumError.getStatus().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    }
}
