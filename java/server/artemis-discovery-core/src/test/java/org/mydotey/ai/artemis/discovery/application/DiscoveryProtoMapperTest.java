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
import org.mydotey.ai.artemis.discovery.domain.EjectionRule;
import org.mydotey.ai.artemis.discovery.domain.EjectionTarget;
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
    void should_map_clusters_between_proto_and_domain() {
        // QueryRequest → domain：clusters 契约原样（可能重复，归一在应用服务）
        org.mydotey.ai.artemis.discovery.domain.QueryRequest domain = mapper.toDomain(QueryRequest.newBuilder()
                .addClusters("cluster-a")
                .addClusters("cluster-a")
                .build());
        assertThat(domain.clusters()).containsExactly("cluster-a", "cluster-a");

        // SubscribeRequest → selector：Set 去重 + matchesCluster 语义
        DiscoverySelector selector =
                mapper.toSelector(org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest.newBuilder()
                        .addClusters("cluster-a")
                        .addClusters("cluster-a")
                        .build());
        assertThat(selector.clusters()).containsExactly("cluster-a");
        assertThat(selector.matchesCluster("cluster-a")).isTrue();
        assertThat(selector.matchesCluster("cluster-b")).isFalse();

        // 空集恒真（union 默认）
        DiscoverySelector all =
                mapper.toSelector(org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest.newBuilder()
                        .build());
        assertThat(all.matchesCluster("any")).isTrue();

        // 空白 cluster 名：requireValid 抛 IllegalArgumentException（门面转 INVALID_ARGUMENT）
        DiscoverySelector blank =
                mapper.toSelector(org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest.newBuilder()
                        .addClusters(" ")
                        .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> blank.requireValid())
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    @Test
    void should_map_ejection_rule_events_by_presence() {
        // 快照（含空集判定，D-E7）：snapshot 已设 = 快照事件
        org.mydotey.ai.artemis.proto.common.v1.ServiceKey protoKey =
                org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                        .setNamespace("default")
                        .setService("order-service")
                        .build();
        org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent snapshot =
                org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                        .setSnapshot(org.mydotey.ai.artemis.proto.management.v1.EjectionRulesSnapshot.newBuilder()
                                .addRules(org.mydotey.ai.artemis.proto.management.v1.EjectionRule.newBuilder()
                                        .setService(protoKey)
                                        .setInstanceId("i1")
                                        .setReason("maintenance")
                                        .build())
                                .build())
                        .build();
        assertThat(mapper.isSnapshot(snapshot)).isTrue();
        List<EjectionRule> rules = mapper.toDomain(snapshot.getSnapshot());
        assertThat(rules)
                .containsExactly(
                        EjectionRule.instance(new ServiceKey("default", "order-service"), "i1", "maintenance"));

        // selector 级规则（v0.4 phase-3，scope + target_value）
        org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent selectorDelta =
                org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                        .setEjected(org.mydotey.ai.artemis.proto.management.v1.EjectionRule.newBuilder()
                                .setScope(org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_ZONE)
                                .setTargetValue("dc-2")
                                .setReason("zone down")
                                .build())
                        .build();
        assertThat(mapper.toDomain(selectorDelta.getEjected()))
                .isEqualTo(EjectionRule.selector(
                        org.mydotey.ai.artemis.common.governance.EjectionScope.ZONE, "dc-2", "zone down"));
        org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent selectorRecovered =
                org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                        .setRecovered(org.mydotey.ai.artemis.proto.management.v1.EjectionRecovered.newBuilder()
                                .setScope(
                                        org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_SERVER)
                                .setTargetValue("10.1.1.1")
                                .build())
                        .build();
        assertThat(mapper.toDomain(selectorRecovered.getRecovered()))
                .isEqualTo(new EjectionTarget(
                        org.mydotey.ai.artemis.common.governance.EjectionScope.SERVER, null, null, "10.1.1.1"));

        // 空快照（snapshot 已设、rules 空）
        org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent empty =
                org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                        .setSnapshot(org.mydotey.ai.artemis.proto.management.v1.EjectionRulesSnapshot.newBuilder())
                        .build();
        assertThat(mapper.isSnapshot(empty)).isTrue();
        assertThat(mapper.toDomain(empty.getSnapshot())).isEmpty();

        // 增量（snapshot 未设）
        org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent delta =
                org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                        .setEjected(org.mydotey.ai.artemis.proto.management.v1.EjectionRule.newBuilder()
                                .setService(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                        .setNamespace("default")
                                        .setService("order-service"))
                                .setInstanceId("i2")
                                .build())
                        .build();
        assertThat(mapper.isSnapshot(delta)).isFalse();
        assertThat(mapper.toDomain(delta.getEjected()))
                .isEqualTo(EjectionRule.instance(new ServiceKey("default", "order-service"), "i2", ""));
    }
}
