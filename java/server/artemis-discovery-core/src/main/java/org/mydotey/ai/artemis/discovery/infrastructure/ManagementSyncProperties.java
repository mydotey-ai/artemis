package org.mydotey.ai.artemis.discovery.infrastructure;

import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.facade.StringProperties;

/**
 * management 上游同步共享参数（ejection / route-rule 两同步器同源，phase-2 抽出）：
 * 同一 SCF key 全局只允许一个 PropertyConfig（manager 按 key 缓存并校验一致性，
 * 含 doc）——共享配置以配置值对象统一定义一次（规范 §10.1），不重复声明。
 */
public final class ManagementSyncProperties {

    static final String KEY_QUEUE_CAPACITY = "artemis.discovery.management.queueCapacity";
    static final String KEY_BACKOFF_MIN_MS = "artemis.discovery.management.reconnectBackoffMinMs";
    static final String KEY_BACKOFF_MAX_MS = "artemis.discovery.management.reconnectBackoffMaxMs";

    private static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    private static final long DEFAULT_BACKOFF_MIN_MS = 1_000;
    private static final long DEFAULT_BACKOFF_MAX_MS = 10_000;

    final int queueCapacity;
    final long backoffMinMs;
    final long backoffMaxMs;

    public ManagementSyncProperties(StringProperties properties) {
        this.queueCapacity = ScfProperties.staticInt(
                        properties,
                        KEY_QUEUE_CAPACITY,
                        DEFAULT_QUEUE_CAPACITY,
                        16,
                        1_048_576,
                        "ejection rule event queue capacity (static)")
                .getValue();
        this.backoffMinMs = ScfProperties.staticLong(
                        properties,
                        KEY_BACKOFF_MIN_MS,
                        DEFAULT_BACKOFF_MIN_MS,
                        100,
                        600_000,
                        KEY_BACKOFF_MIN_MS + " (static)")
                .getValue();
        this.backoffMaxMs = ScfProperties.staticLong(
                        properties,
                        KEY_BACKOFF_MAX_MS,
                        DEFAULT_BACKOFF_MAX_MS,
                        100,
                        600_000,
                        KEY_BACKOFF_MAX_MS + " (static)")
                .getValue();
    }
}
