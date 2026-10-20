package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;

class ReplicationProtoFacadeTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC);
    private static final ClusterMember NODE_A = new ClusterMember("node-a", new ClusterEndpoint("10.0.0.1", 9093));
    private static final ClusterMember NODE_B = new ClusterMember("node-b", new ClusterEndpoint("10.0.0.2", 9093));

    @Test
    void should_report_starting_then_up_when_readiness_changes() {
        ReadinessGate gate = new ReadinessGate();
        ReplicationService service = new ReplicationServiceImpl(members(NODE_A, NODE_B), gate, CLOCK);
        ReplicationProtoFacade facade = new ReplicationProtoFacade(service);

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
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(new ReplicationServiceImpl(members(NODE_A, NODE_B), gate, CLOCK));

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
                        },
                        member -> {
                            assertThat(member.getNodeId()).isEqualTo("node-b");
                            assertThat(member.getPeerHost()).isEqualTo("10.0.0.2");
                            assertThat(member.getPeerPort()).isEqualTo(9093);
                        });
    }

    @Test
    void should_bracket_ipv6_peer_host_when_mapping_to_wire() {
        ClusterMembers ipv6Members = new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of(new ClusterMember("node-v6", new ClusterEndpoint("fd00::1", 9093)));
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(members().get(0));
            }
        };
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(new ReplicationServiceImpl(ipv6Members, new ReadinessGate(), CLOCK));

        assertThat(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getData()
                        .getMembers(0)
                        .getPeerHost())
                .isEqualTo("[fd00::1]");
    }

    @Test
    void should_report_blank_node_id_when_single_node() {
        ReplicationProtoFacade facade =
                new ReplicationProtoFacade(new ReplicationServiceImpl(singleNode(), new ReadinessGate(), CLOCK));

        GetNodeStatusResponse response = facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance());

        assertThat(response.getData().getSelf().getNodeId()).isEmpty();
        assertThat(response.getData().getMembersList()).isEmpty();
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
