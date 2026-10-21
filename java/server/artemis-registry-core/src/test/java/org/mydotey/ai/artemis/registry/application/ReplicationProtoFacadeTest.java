package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.PeerStatusView;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

class ReplicationProtoFacadeTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC);
    private static final ClusterMember NODE_A =
            new ClusterMember("node-a", new ClusterEndpoint("10.0.0.1", 9093), new ClusterEndpoint("10.0.0.1", 9090));
    private static final ClusterMember NODE_B =
            new ClusterMember("node-b", new ClusterEndpoint("10.0.0.2", 9093), new ClusterEndpoint("10.0.0.2", 9090));
    private static final PeerStatusView PEER_UP = nodeId -> org.mydotey.ai.artemis.registry.domain.NodeState.UP;
    private static final PeerStatusView PEER_UNKNOWN =
            nodeId -> org.mydotey.ai.artemis.registry.domain.NodeState.UNKNOWN;

    @Test
    void should_report_starting_then_up_when_readiness_changes() {
        ReadinessGate gate = new ReadinessGate();
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(service(members(NODE_A, NODE_B), gate, PEER_UNKNOWN));

        assertThat(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getData()
                        .getSelf()
                        .getState())
                .isEqualTo(NodeState.NODE_STATE_STARTING);

        gate.markReady();

        assertThat(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getData()
                        .getSelf()
                        .getState())
                .isEqualTo(NodeState.NODE_STATE_UP);
    }

    @Test
    void should_map_members_and_self_when_node_status_queried() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        ReplicationProtoFacade facade = new ReplicationProtoFacade(service(members(NODE_A, NODE_B), gate, PEER_UP));

        GetNodeStatusResponse response = facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getSelf().getNodeId()).isEqualTo("node-a");
        assertThat(response.getData().getSelf().getStartedAtMs()).isEqualTo(NOW_MS);
        assertThat(response.getData().getMembersList())
                .satisfiesExactly(
                        member -> {
                            assertThat(member.getNodeId()).isEqualTo("node-a");
                            assertThat(member.getPeerHost()).isEqualTo("10.0.0.1");
                            assertThat(member.getPeerPort()).isEqualTo(9093);
                            assertThat(member.getExternalHost()).isEqualTo("10.0.0.1");
                            assertThat(member.getExternalPort()).isEqualTo(9090);
                            assertThat(member.getState()).isEqualTo(NodeState.NODE_STATE_UP);
                        },
                        member -> {
                            assertThat(member.getNodeId()).isEqualTo("node-b");
                            assertThat(member.getPeerHost()).isEqualTo("10.0.0.2");
                            assertThat(member.getPeerPort()).isEqualTo(9093);
                            assertThat(member.getExternalHost()).isEqualTo("10.0.0.2");
                            assertThat(member.getExternalPort()).isEqualTo(9090);
                            assertThat(member.getState()).isEqualTo(NodeState.NODE_STATE_UP);
                        });
    }

    @Test
    void should_bracket_ipv6_peer_host_when_mapping_to_wire() {
        ClusterMembers ipv6Members = new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of(new ClusterMember(
                        "node-v6", new ClusterEndpoint("fd00::1", 9093), new ClusterEndpoint("fd00::1", 9090)));
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(members().get(0));
            }
        };
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(service(ipv6Members, new ReadinessGate(), PEER_UNKNOWN));

        assertThat(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getData()
                        .getMembers(0)
                        .getPeerHost())
                .isEqualTo("[fd00::1]");
        assertThat(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getData()
                        .getMembers(0)
                        .getExternalHost())
                .isEqualTo("[fd00::1]");
    }

    @Test
    void should_report_blank_node_id_when_single_node() {
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(service(singleNode(), new ReadinessGate(), PEER_UNKNOWN));

        GetNodeStatusResponse response = facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance());

        assertThat(response.getData().getSelf().getNodeId()).isEmpty();
        assertThat(response.getData().getMembersList()).isEmpty();
    }

    @Test
    void should_apply_replicated_batch_and_ack_success_when_replicate_called() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        MutableClock clock = new MutableClock();
        List<org.mydotey.ai.artemis.registry.domain.InstanceChange> events = new java.util.ArrayList<>();
        InstanceRegistry registry = new InstanceRegistry(TestProperties.empty(), clock, events::add);
        ReplicationProtoFacade facade = new ReplicationProtoFacade(new ReplicationServiceImpl(
                members(NODE_A, NODE_B), gate, PEER_UNKNOWN, registry, CLOCK, new SimpleMeterRegistry()));

        org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse response =
                facade.replicate(org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest.newBuilder()
                        .setBatch(org.mydotey.ai.artemis.proto.replication.v1.ReplicationBatch.newBuilder()
                                .setSourceNodeId("node-b")
                                .addServices(org.mydotey.ai.artemis.proto.replication.v1.ServiceChange.newBuilder()
                                        .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                                .setNamespace("default")
                                                .setService("order-service"))
                                        .addChanges(
                                                org.mydotey.ai.artemis.proto.replication.v1.InstanceChange.newBuilder()
                                                        .setInstance(
                                                                org.mydotey.ai.artemis.proto.common.v1.Instance
                                                                        .newBuilder()
                                                                        .setInstanceId("i1"))
                                                        .setType(
                                                                org.mydotey.ai.artemis.proto.replication.v1.ChangeType
                                                                        .CHANGE_TYPE_RENEW))))
                        .build());

        org.assertj.core.api.Assertions.assertThat(response.getStatus().getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
        org.assertj.core.api.Assertions.assertThat(events).isEmpty(); // 零事件（防回环）
        org.assertj.core.api.Assertions.assertThat(registry.snapshot(
                        new org.mydotey.ai.artemis.registry.domain.ServiceKey("default", "order-service")))
                .hasValueSatisfying(s -> org.assertj.core.api.Assertions.assertThat(s.instances())
                        .hasSize(1)); // RENEW 缺席补注册
    }

    @Test
    void should_export_full_registry_snapshot_for_bootstrap() {
        InstanceRegistry registry = new InstanceRegistry(TestProperties.empty(), new MutableClock(), change -> {});
        registry.restore(List.of(new ServiceInstances(
                new ServiceKey("default", "order-service"),
                List.of(new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, java.util.Map.of())))));
        ReplicationProtoFacade facade = new ReplicationProtoFacade(new ReplicationServiceImpl(
                members(NODE_A, NODE_B),
                new ReadinessGate(),
                PEER_UNKNOWN,
                registry,
                CLOCK,
                new SimpleMeterRegistry()));

        List<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> snapshot =
                facade.bootstrapSnapshotStream().toList();

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getKey().getService()).isEqualTo("order-service");
        assertThat(snapshot.get(0).getInstancesCount()).isEqualTo(1);
    }

    @Test
    void should_export_empty_snapshot_when_registry_empty() {
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(service(members(NODE_A, NODE_B), new ReadinessGate(), PEER_UNKNOWN));

        assertThat(facade.bootstrapSnapshotStream()).isEmpty();
    }

    private static ReplicationService service(
            ClusterMembers members, ReadinessGate gate, PeerStatusView peerStatusView) {
        return new ReplicationServiceImpl(
                members,
                gate,
                peerStatusView,
                new InstanceRegistry(TestProperties.empty(), new MutableClock(), change -> {}),
                CLOCK,
                new SimpleMeterRegistry());
    }

    private static ClusterMembers members(ClusterMember... members) {
        List<ClusterMember> list = List.of(members);
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return list;
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(list.get(0));
            }
        };
    }

    private static ClusterMembers singleNode() {
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of();
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.empty();
            }
        };
    }
}
