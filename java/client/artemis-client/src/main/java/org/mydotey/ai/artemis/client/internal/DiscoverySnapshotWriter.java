package org.mydotey.ai.artemis.client.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快照异步落盘（client 级共享单线程）：视图变更置脏即请求，200ms 合并突发；写进行中再有变更
 * 只置脏、写完补写最新（只写最新视图，不阻塞 applier/推送）。
 */
final class DiscoverySnapshotWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoverySnapshotWriter.class);

    private static final long DEBOUNCE_MS = 200;
    private static final int MAX_PENDING_TASKS = 16;

    private final Set<SnapshotSource> dirty =
            Collections.newSetFromMap(new ConcurrentHashMap<SnapshotSource, Boolean>());
    private final BoundedScheduledExecutor executor;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;

    DiscoverySnapshotWriter() {
        this.executor = new BoundedScheduledExecutor(
                "discovery-snapshot",
                1,
                MAX_PENDING_TASKS,
                new NamedDaemonThreadFactory("artemis-client-discovery-snapshot"));
    }

    void request(SnapshotSource source) {
        if (closed) {
            return;
        }
        dirty.add(source);
        schedule();
    }

    void remove(SnapshotSource source) {
        dirty.remove(source);
    }

    private void schedule() {
        if (closed || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            scheduled.set(false);
            LOG.warn("discovery snapshot writer queue full, write deferred");
        }
    }

    private void flush() {
        scheduled.set(false);
        for (SnapshotSource source : new ArrayList<>(dirty)) {
            if (dirty.remove(source)) {
                try {
                    source.writeSnapshot();
                } catch (RuntimeException e) {
                    LOG.warn("discovery snapshot write failed: {}", e.toString());
                }
            }
        }
        if (!dirty.isEmpty()) {
            schedule();
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        flush(); // 终写剩余脏订阅
        executor.shutdownNow();
    }
}
