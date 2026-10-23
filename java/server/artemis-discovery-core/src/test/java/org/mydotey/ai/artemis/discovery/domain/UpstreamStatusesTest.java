package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpstreamStatusesTest {

    @Test
    void should_snapshot_upstream_states_on_updates() {
        UpstreamStatuses statuses = new UpstreamStatuses();
        assertThat(statuses.snapshot()).isEmpty();

        statuses.update("cluster-a", UpstreamState.SYNCING);
        statuses.update("cluster-b", UpstreamState.SYNCED);
        statuses.update("cluster-a", UpstreamState.DISCONNECTED); // 转移覆盖

        assertThat(statuses.snapshot())
                .containsExactlyInAnyOrder(
                        new UpstreamStatus("cluster-a", UpstreamState.DISCONNECTED),
                        new UpstreamStatus("cluster-b", UpstreamState.SYNCED));
    }
}
