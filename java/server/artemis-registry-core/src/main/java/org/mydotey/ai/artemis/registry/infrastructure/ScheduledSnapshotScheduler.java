package org.mydotey.ai.artemis.registry.infrastructure;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.domain.SnapshotScheduler;

/**
 * 领域端口 {@link SnapshotScheduler} 实现：单平台线程按固定延迟周期触发快照写
 * （先例 {@code ScheduledLeaseEvictionScheduler}）。周期可配置（装配注入，默认 5min——
 * 最坏丢失窗口 = 一个快照周期的重注册，快照只是减少重注册风暴而非零丢失）。
 */
@Slf4j
public final class ScheduledSnapshotScheduler implements SnapshotScheduler {

    private final ScheduledExecutorService executor;
    private final Runnable writeSnapshot;
    private final long intervalMs;
    private final AtomicBoolean started = new AtomicBoolean();

    public ScheduledSnapshotScheduler(Duration snapshotInterval, Runnable writeSnapshot) {
        this.writeSnapshot = writeSnapshot;
        this.intervalMs = snapshotInterval.toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("artemis-snapshot-writer").daemon(true).factory());
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("snapshot scheduler already started");
        }
        // 单轮任务抛异常/错误都会静默终止后续调度（scheduleWithFixedDelay 语义），须在任务内全量兜底
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        writeSnapshot.run();
                    } catch (Throwable e) {
                        log.warn("snapshot write round failed, scheduling continues", e);
                    }
                },
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
