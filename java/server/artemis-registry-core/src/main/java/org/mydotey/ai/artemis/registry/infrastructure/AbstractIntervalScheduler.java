package org.mydotey.ai.artemis.registry.infrastructure;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.Property;

/**
 * 单平台线程周期任务骨架：周期为**动态配置**——每轮结束时读取最新值再排下一轮；
 * 运行期改值经 SCF 变更监听立即取消当前排期并按新周期重排（不轮询 diff）。
 * 调度状态只在 executor 线程内变更；generation 防止「监听重排」与「本轮自续」重复排期。
 */
@Slf4j
abstract class AbstractIntervalScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final Property<String, Long> interval;
    private final Runnable task;
    /** 首轮延迟；&lt; 0 表示等一个周期（周期任务），0 表示立即（探测类任务）。 */
    private final long initialDelayMs;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;
    private long generation; // executor-confined

    AbstractIntervalScheduler(String threadName, Property<String, Long> interval, Runnable task) {
        this(threadName, interval, -1, task);
    }

    AbstractIntervalScheduler(String threadName, Property<String, Long> interval, long initialDelayMs, Runnable task) {
        this.interval = interval;
        this.task = task;
        this.initialDelayMs = initialDelayMs;
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(threadName).daemon(true).factory());
        interval.addChangeListener(event -> reschedule());
    }

    public final void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(getClass().getSimpleName() + " already started");
        }
        executor.execute(() -> {
            long gen = ++generation;
            schedule(gen, initialDelayMs >= 0 ? initialDelayMs : interval.getValue());
        });
    }

    private void scheduleNext(long gen) {
        schedule(gen, interval.getValue());
    }

    private void schedule(long gen, long delayMs) {
        if (!started.get() || gen != generation) {
            return;
        }
        try {
            future = executor.schedule(() -> runRound(gen), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 关闭中
        }
    }

    private void runRound(long gen) {
        if (!started.get() || gen != generation) {
            return;
        }
        // 单轮任务抛异常/错误后必须继续调度
        try {
            task.run();
        } catch (Throwable e) {
            log.warn("scheduled round failed, scheduling continues", e);
        }
        scheduleNext(gen);
    }

    private void reschedule() {
        if (!started.get()) {
            return;
        }
        try {
            executor.execute(() -> {
                if (!started.get()) {
                    return;
                }
                long gen = ++generation;
                if (future != null) {
                    future.cancel(false);
                }
                scheduleNext(gen);
            });
        } catch (RejectedExecutionException ignored) {
            // 关闭中
        }
    }

    @Override
    public final void close() {
        started.set(false);
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdown();
        onClose();
    }

    /** 子类释放额外资源（在调度线程关闭后调用）。 */
    protected void onClose() {}
}
