package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;

class StaticClusterMembersTest {

    @Test
    void should_parse_cluster_members_when_valid_comma_separated() {
        StaticClusterMembers members = members("10.0.0.1:9092, node-b:9092");

        assertThat(members.members())
                .containsExactly(
                        new ClusterMember("10.0.0.1:9092", "10.0.0.1", 9092),
                        new ClusterMember("node-b:9092", "node-b", 9092));
    }

    @Test
    void should_return_empty_when_members_blank() {
        assertThat(members("").members()).isEmpty();
        assertThat(members(" , ").members()).isEmpty();
    }

    @Test
    void should_fail_fast_when_cluster_member_malformed() {
        assertThatThrownBy(() -> members("10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
    }

    @Test
    void should_fail_fast_when_cluster_member_port_out_of_range() {
        assertThatThrownBy(() -> members("10.0.0.1:70000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
    }

    @Test
    void should_parse_bracketed_ipv6_member() {
        assertThat(members("[fd00::1]:9092").members())
                .containsExactly(new ClusterMember("[fd00::1]:9092", "fd00::1", 9092));
    }

    @Test
    void should_reject_bare_ipv6_member_without_brackets() {
        assertThatThrownBy(() -> members("::1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StaticClusterMembers.KEY_MEMBERS);
    }

    private static StaticClusterMembers members(String raw) {
        return new StaticClusterMembers(
                TestProperties.of(Map.of(StaticClusterMembers.KEY_MEMBERS, raw)).properties());
    }
}
