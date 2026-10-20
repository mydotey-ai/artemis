package org.mydotey.ai.artemis.registry.infrastructure;

import org.mydotey.ai.artemis.registry.domain.SnapshotScheduler;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 领域端口 {@link SnapshotScheduler} 实现：单平台线程按动态周期触发快照写
 * （先例 {@code ScheduledLeaseEvictionScheduler}）。默认 5min——最坏丢失窗口 =
 * 一个快照周期的重注册，快照只是减少重注册风暴而非零丢失。周期运行期可调。
 */
public final class ScheduledSnapshotScheduler extends AbstractIntervalScheduler implements SnapshotScheduler {

    static final String KEY_INTERVAL_MS = "artemis.registry.snapshot.intervalMs";
    private static final long DEFAULT_INTERVAL_MS = 300_000;

    public ScheduledSnapshotScheduler(StringProperties properties, Runnable writeSnapshot) {
        super(
                "artemis-snapshot-writer",
                properties.getLongProperty(
                        KEY_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_INTERVAL_MS, 1_000L, 86_400_000L)),
                writeSnapshot);
    }

    /** 直接 Property 构造（测试用短周期）。 */
    ScheduledSnapshotScheduler(Property<String, Long> interval, Runnable writeSnapshot) {
        super("artemis-snapshot-writer", interval, writeSnapshot);
    }
}
