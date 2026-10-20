package org.mydotey.ai.artemis.registry.infrastructure;

import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 领域端口 {@link LeaseEvictionScheduler} 实现：单平台线程按动态周期触发清理入口。
 * 周期运行期可调（SCF 监听即时重排，见 {@link AbstractIntervalScheduler}）。
 */
public final class ScheduledLeaseEvictionScheduler extends AbstractIntervalScheduler implements LeaseEvictionScheduler {

    static final String KEY_INTERVAL_MS = "artemis.registry.lease.evictionIntervalMs";
    private static final long DEFAULT_INTERVAL_MS = 1_000;

    public ScheduledLeaseEvictionScheduler(StringProperties properties, Runnable evictExpired) {
        super(
                "artemis-lease-eviction",
                properties.getLongProperty(KEY_INTERVAL_MS, new RangeValueConfig<>(DEFAULT_INTERVAL_MS, 100L, 60_000L)),
                evictExpired);
    }

    /** 直接 Property 构造（测试用短周期）。 */
    ScheduledLeaseEvictionScheduler(Property<String, Long> interval, Runnable evictExpired) {
        super("artemis-lease-eviction", interval, evictExpired);
    }
}
