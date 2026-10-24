package org.mydotey.ai.artemis.client;

/**
 * discovery 上游（registry 集群）同步状态：非 {@code SYNCED} 即数据可能陈旧（新鲜度标记，
 * 消费方感知降级）。对不能识别的 wire 枚举值映射 {@link #UNKNOWN}。
 */
public enum UpstreamState {
    UNKNOWN,
    SYNCING,
    SYNCED,
    DISCONNECTED
}
