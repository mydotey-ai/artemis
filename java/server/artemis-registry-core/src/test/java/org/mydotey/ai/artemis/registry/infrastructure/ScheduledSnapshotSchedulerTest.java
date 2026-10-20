package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.TestProperty;

/** 快照调度器（phase-2 调度器同形态）：周期触发、生命周期防护（重复 start 快速失败）、Throwable 兜底后存活。 */
class ScheduledSnapshotSchedulerTest {

    @Test
    void should_snapshot_on_schedule_and_close_cleanly() {
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledSnapshotScheduler scheduler =
                new ScheduledSnapshotScheduler(TestProperty.fixed(5L), rounds::incrementAndGet)) {
            scheduler.start();
            TestAwait.await("snapshot rounds triggered", () -> rounds.get() >= 3);
            assertThat(rounds.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void should_reject_duplicate_start_when_started_twice() {
        try (ScheduledSnapshotScheduler scheduler =
                new ScheduledSnapshotScheduler(TestProperty.fixed(1_000L), () -> {})) {
            scheduler.start();
            assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void should_use_latest_interval_when_changed() {
        TestProperties properties = TestProperties.of(Map.of(ScheduledSnapshotScheduler.KEY_INTERVAL_MS, "60000"));
        org.mydotey.scf.Property<String, Long> interval = properties
                .properties()
                .getLongProperty(
                        ScheduledSnapshotScheduler.KEY_INTERVAL_MS,
                        new org.mydotey.scf.filter.RangeValueConfig<>(60_000L, 1L, 86_400_000L));
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledSnapshotScheduler scheduler = new ScheduledSnapshotScheduler(interval, rounds::incrementAndGet)) {
            scheduler.start();
            // 运行期把 60s 周期改短：监听器应取消原排期并按新周期重排
            properties.set(ScheduledSnapshotScheduler.KEY_INTERVAL_MS, "20");
            TestAwait.await("interval change applied", () -> rounds.get() >= 3);
        }
    }

    @Test
    void should_schedule_using_configured_interval() {
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledSnapshotScheduler scheduler = new ScheduledSnapshotScheduler(
                TestProperties.of(Map.of(ScheduledSnapshotScheduler.KEY_INTERVAL_MS, "1000"))
                        .properties(),
                rounds::incrementAndGet)) {
            scheduler.start();
            TestAwait.await("configured interval applied", () -> rounds.get() >= 1);
        }
    }

    @Test
    void should_continue_scheduling_when_round_throws_error() {
        AtomicInteger rounds = new AtomicInteger();
        try (ScheduledSnapshotScheduler scheduler = new ScheduledSnapshotScheduler(TestProperty.fixed(5L), () -> {
            if (rounds.incrementAndGet() == 1) {
                // Error 未被兜底时 scheduleWithFixedDelay 会终止后续全部轮次
                throw new AssertionError("first round dies");
            }
        })) {
            scheduler.start();
            TestAwait.await("scheduling survives first-round error", () -> rounds.get() >= 3);
        }
    }
}
