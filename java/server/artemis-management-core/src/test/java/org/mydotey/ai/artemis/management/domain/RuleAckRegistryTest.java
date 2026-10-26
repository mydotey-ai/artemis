package org.mydotey.ai.artemis.management.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 生效 ack 登记（phase-5）：水位只反映活订阅、取 max、未知订阅方忽略。 */
class RuleAckRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private final RuleAckRegistry acks = new RuleAckRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void should_track_watermark_when_subscriber_acks() {
        acks.register("sub-1");

        // 订阅建立尚未 ack：appliedVersion 未知（null）
        assertThat(acks.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.subscriberId()).isEqualTo("sub-1");
            assertThat(entry.appliedVersion()).isNull();
        });

        // 乱序/重复上报取 max，lastAckAt 总是刷新
        acks.record("sub-1", 5);
        acks.record("sub-1", 3);
        acks.record("sub-1", 5);
        assertThat(acks.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.appliedVersion()).isEqualTo(5L);
            assertThat(entry.lastAckAt()).isEqualTo(NOW);
        });
    }

    @Test
    void should_evict_watermark_when_subscriber_gone() {
        RuleAckRegistry.Subscription subscription = acks.register("sub-1");
        acks.record("sub-1", 5);
        acks.evict("sub-1", subscription);

        assertThat(acks.entries()).isEmpty();
    }

    @Test
    void should_keep_new_generation_when_stale_subscription_evicts() {
        // 同 id 重连：新流先登记，旧流的迟到取消/断流注销不得误删新条目
        RuleAckRegistry.Subscription stale = acks.register("sub-1");
        RuleAckRegistry.Subscription current = acks.register("sub-1");
        acks.record("sub-1", 5);

        acks.evict("sub-1", stale); // 旧代际注销：无效果

        assertThat(acks.entries()).hasSize(1);
        assertThat(acks.entries().get(0).appliedVersion()).isEqualTo(5L);

        acks.evict("sub-1", current); // 当前代际注销：正常清除
        assertThat(acks.entries()).isEmpty();
    }

    @Test
    void should_ignore_ack_from_unknown_subscriber() {
        acks.record("ghost", 5); // 未登记：忽略，不建条目（免无界滞留）

        assertThat(acks.entries()).isEmpty();
    }

    @Test
    void should_reset_watermark_when_same_subscriber_reconnects() {
        acks.register("sub-1");
        acks.record("sub-1", 5);
        acks.register("sub-1"); // 同 id 重连：水位重置（等待新快照 ack）

        assertThat(acks.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.appliedVersion()).isNull());
    }

    @Test
    void should_sort_entries_by_subscriber_id() {
        acks.register("sub-b");
        acks.register("sub-a");
        acks.record("sub-a", 1);
        acks.record("sub-b", 1);

        assertThat(acks.entries())
                .extracting(RuleAckRegistry.AckEntry::subscriberId)
                .containsExactly("sub-a", "sub-b");
    }
}
