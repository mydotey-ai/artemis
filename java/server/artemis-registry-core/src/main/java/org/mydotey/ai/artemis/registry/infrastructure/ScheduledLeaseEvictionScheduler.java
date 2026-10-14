package org.mydotey.ai.artemis.registry.infrastructure;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;

/**
 * 领域端口 {@link LeaseEvictionScheduler} 实现：单平台线程按固定延迟周期触发清理入口。
 * 构造即创建执行器，{@link #start()} 起调度（重复调用快速失败），{@link #close()} 释放线程。
 */
@Slf4j
public final class ScheduledLeaseEvictionScheduler implements LeaseEvictionScheduler {

    private final ScheduledExecutorService executor;
    private final Runnable evictExpired;
    private final long intervalMs;
    private final AtomicBoolean started = new AtomicBoolean();

    public ScheduledLeaseEvictionScheduler(Duration cleanupInterval, Runnable evictExpired) {
        this.evictExpired = evictExpired;
        this.intervalMs = cleanupInterval.toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("artemis-lease-eviction").daemon(true).factory());
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("lease eviction scheduler already started");
        }
        // 单轮任务抛异常/错误都会静默终止后续调度（scheduleWithFixedDelay 语义），须在任务内全量兜底
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        evictExpired.run();
                    } catch (Throwable e) {
                        log.warn("lease eviction round failed, scheduling continues", e);
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
