package org.mydotey.ai.artemis.client.internal;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 有界 ScheduledThreadPoolExecutor：{@link ScheduledThreadPoolExecutor} 固定使用无界
 * DelayedWorkQueue（guidelines §4.2 禁无界队列），故在入队口按待执行任务数做硬上限——
 * 超限抛 {@link RejectedExecutionException}（由调用方记录并降级，不无界堆积）。
 */
final class BoundedScheduledExecutor extends ScheduledThreadPoolExecutor {

    private final int maxPending;
    private final String name;

    BoundedScheduledExecutor(String name, int corePoolSize, int maxPending, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
        this.name = name;
        this.maxPending = maxPending;
        setRemoveOnCancelPolicy(true);
    }

    @Override
    public void execute(Runnable command) {
        ensureCapacity();
        super.execute(command);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ensureCapacity();
        return super.schedule(command, delay, unit);
    }

    private void ensureCapacity() {
        if (getQueue().size() >= maxPending) {
            throw new RejectedExecutionException(name + " queue full (max " + maxPending + ")");
        }
    }

    /** 排障可读名称（日志/指标）。 */
    String name() {
        return name;
    }
}
