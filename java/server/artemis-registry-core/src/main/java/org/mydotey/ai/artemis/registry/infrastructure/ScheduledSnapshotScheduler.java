package org.mydotey.ai.artemis.registry.infrastructure;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.SnapshotScheduler;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 领域端口 {@link SnapshotScheduler} 实现：单平台线程按固定延迟周期触发快照写
 * （先例 {@code ScheduledLeaseEvictionScheduler}）。周期可配置（装配注入，默认 5min——
 * 最坏丢失窗口 = 一个快照周期的重注册，快照只是减少重注册风暴而非零丢失）。
 */
@Slf4j
public final class ScheduledSnapshotScheduler implements SnapshotScheduler {

    static final String KEY_INTERVAL_MS = "artemis.registry.snapshot.intervalMs";
    private static final long DEFAULT_INTERVAL_MS = 300_000;

    private final ScheduledExecutorService executor;
    private final Runnable writeSnapshot;
    private final Property<String, Long> interval;
    private final AtomicBoolean started = new AtomicBoolean();

    public ScheduledSnapshotScheduler(StringProperties properties, Runnable writeSnapshot) {
        this(
                ScfProperties.staticLong(
                        properties,
                        KEY_INTERVAL_MS,
                        DEFAULT_INTERVAL_MS,
                        1_000L,
                        86_400_000L,
                        "snapshot write interval in milliseconds (static, restart to apply)"),
                writeSnapshot);
    }

    /** 直接 Property 构造（测试用短周期）。 */
    ScheduledSnapshotScheduler(Property<String, Long> interval, Runnable writeSnapshot) {
        this.interval = interval;
        this.writeSnapshot = writeSnapshot;
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("artemis-snapshot-writer").daemon(true).factory());
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("snapshot scheduler already started");
        }
        long intervalMs = interval.getValue();
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
