package org.mydotey.ai.artemis.registry.infrastructure;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 领域端口 {@link LeaseEvictionScheduler} 实现：单平台线程按固定延迟周期触发清理入口。
 * 构造即创建执行器，{@link #start()} 起调度（重复调用快速失败），{@link #close()} 释放线程。
 */
@Slf4j
public final class ScheduledLeaseEvictionScheduler implements LeaseEvictionScheduler {

    static final String KEY_INTERVAL_MS = "artemis.registry.lease.evictionIntervalMs";
    private static final long DEFAULT_INTERVAL_MS = 1_000;

    private final ScheduledExecutorService executor;
    private final Runnable evictExpired;
    private final Property<String, Long> interval;
    private final AtomicBoolean started = new AtomicBoolean();

    public ScheduledLeaseEvictionScheduler(StringProperties properties, Runnable evictExpired) {
        this(
                ScfProperties.staticLong(
                        properties,
                        KEY_INTERVAL_MS,
                        DEFAULT_INTERVAL_MS,
                        100L,
                        60_000L,
                        "lease eviction interval in milliseconds (static, restart to apply)"),
                evictExpired);
    }

    /** 直接 Property 构造（测试用短周期）。 */
    ScheduledLeaseEvictionScheduler(Property<String, Long> interval, Runnable evictExpired) {
        this.interval = interval;
        this.evictExpired = evictExpired;
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("artemis-lease-eviction").daemon(true).factory());
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("lease eviction scheduler already started");
        }
        long intervalMs = interval.getValue();
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
