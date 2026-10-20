package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;

class StaticClusterMembersTest {

    @Test
    void should_parse_members_when_node_id_and_peer_configured() {
        StaticClusterMembers members = members("node-a@10.0.0.1:9093, node-b@node-b:9093", "node-a");

        assertThat(members.members())
                .containsExactly(
                        new ClusterMember("node-a", new ClusterEndpoint("10.0.0.1", 9093)),
                        new ClusterMember("node-b", new ClusterEndpoint("node-b", 9093)));
    }

    @Test
    void should_identify_self_when_self_configured() {
        StaticClusterMembers members = members("node-a@10.0.0.1:9093,node-b@10.0.0.2:9093", "node-b");

        assertThat(members.self()).contains(new ClusterMember("node-b", new ClusterEndpoint("10.0.0.2", 9093)));
    }

    @Test
    void should_return_empty_self_when_single_node() {
        StaticClusterMembers members = members("", "");

        assertThat(members.members()).isEmpty();
        assertThat(members.self()).isEmpty();
    }

    @Test
    void should_fail_fast_when_member_malformed_or_duplicate() {
        assertThatThrownBy(() -> members("10.0.0.1:9093", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
        assertThatThrownBy(() -> members("node-a@10.0.0.1:9093@x", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
        assertThatThrownBy(() -> members("node-a@10.0.0.1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
        assertThatThrownBy(() -> members("node-a@10.0.0.1:70000", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
        assertThatThrownBy(() -> members("node-a@10.0.0.1:9093,node-a@10.0.0.2:9093", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void should_fail_fast_when_self_missing_or_unknown() {
        assertThatThrownBy(() -> members("node-a@10.0.0.1:9093", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_SELF);
        assertThatThrownBy(() -> members("node-a@10.0.0.1:9093", "node-z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_SELF);
        assertThatThrownBy(() -> members("", "node-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_SELF);
    }

    @Test
    void should_parse_bracketed_ipv6_member() {
        assertThat(members("[fd00::1]@[fd00::1]:9093", "[fd00::1]").members())
                .containsExactly(new ClusterMember("[fd00::1]", new ClusterEndpoint("fd00::1", 9093)));
    }

    @Test
    void should_reject_bare_ipv6_member_without_brackets() {
        assertThatThrownBy(() -> members("node-a@::1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
    }

    @Test
    void should_fail_fast_when_member_host_invalid() {
        assertThatThrownBy(() -> members("node-a@foo bar:9093", "node-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
        assertThatThrownBy(() -> members("node-a@[:::]:9093", "node-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
    }

    private static StaticClusterMembers members(String rawMembers, String self) {
        return new StaticClusterMembers(TestProperties.of(
                        Map.of(StaticClusterMembers.KEY_MEMBERS, rawMembers, StaticClusterMembers.KEY_SELF, self))
                .properties());
    }
}
