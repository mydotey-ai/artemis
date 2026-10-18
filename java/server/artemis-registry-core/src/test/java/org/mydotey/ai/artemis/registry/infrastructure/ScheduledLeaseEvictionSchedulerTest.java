package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.TestProperty;

/**
 * 领域端口实现的直接单测（IT 仅经 Spring initMethod 间接触发一次 start）：
 * 生命周期防护（重复 start 快速失败）与 Throwable 兜底后调度存活。
 */
class ScheduledLeaseEvictionSchedulerTest {

    @Test
    void should_reject_duplicate_start_when_started_twice() {
        try (ScheduledLeaseEvictionScheduler scheduler =
                new ScheduledLeaseEvictionScheduler(TestProperty.fixed(1_000L), () -> {})) {
            scheduler.start();
            assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void should_continue_scheduling_when_round_throws_error() {
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledLeaseEvictionScheduler scheduler =
                new ScheduledLeaseEvictionScheduler(TestProperty.fixed(5L), () -> {
                    if (rounds.incrementAndGet() == 1) {
                        // Error 未被兜底时 scheduleWithFixedDelay 会终止后续全部轮次
                        throw new AssertionError("first round dies");
                    }
                })) {
            scheduler.start();
            TestAwait.await("scheduling survives first-round error", () -> rounds.get() >= 3);
            assertThat(rounds.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void should_schedule_using_configured_interval() {
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledLeaseEvictionScheduler scheduler = new ScheduledLeaseEvictionScheduler(
                TestProperties.of(Map.of(ScheduledLeaseEvictionScheduler.KEY_INTERVAL_MS, "100"))
                        .properties(),
                rounds::incrementAndGet)) {
            scheduler.start();
            TestAwait.await("configured interval applied", () -> rounds.get() >= 1);
        }
    }
}
