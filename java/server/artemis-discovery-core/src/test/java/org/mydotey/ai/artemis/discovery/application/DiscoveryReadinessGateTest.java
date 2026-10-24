package org.mydotey.ai.artemis.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 全量同步门（phase-5）：全部配置上游首轮 SYNCED 才 ready，cluster 去重累计、只升不降。 */
class DiscoveryReadinessGateTest {

    @Test
    void should_be_ready_only_after_all_upstreams_synced() {
        DiscoveryReadinessGate gate = new DiscoveryReadinessGate(2);
        assertThat(gate.isReady()).isFalse();

        gate.markSynced("cluster-a");
        assertThat(gate.isReady()).as("N-1 个上游已同步不 ready").isFalse();

        gate.markSynced("cluster-b");
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void should_dedup_repeated_mark_synced() {
        DiscoveryReadinessGate gate = new DiscoveryReadinessGate(2);
        gate.markSynced("cluster-a");
        gate.markSynced("cluster-a"); // 同 cluster 重复上报只计一次
        assertThat(gate.isReady()).isFalse();

        gate.markSynced("cluster-b");
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void should_reject_non_positive_expected_upstreams() {
        assertThatThrownBy(() -> new DiscoveryReadinessGate(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
