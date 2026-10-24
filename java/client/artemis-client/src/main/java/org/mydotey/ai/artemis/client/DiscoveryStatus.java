package org.mydotey.ai.artemis.client;

import java.util.Collections;
import java.util.Map;

/**
 * 单个订阅的当前状态快照（不可变）：连接态、快照回放标记与最近一次拉取的上游新鲜度。
 */
public final class DiscoveryStatus {

    private static final DiscoveryStatus EMPTY =
            new DiscoveryStatus(false, false, 0L, Collections.<String, UpstreamState>emptyMap());

    private final boolean connected;
    private final boolean snapshotReplay;
    private final long snapshotTimestampMs;
    private final Map<String, UpstreamState> upstreamStates;

    public DiscoveryStatus(
            boolean connected,
            boolean snapshotReplay,
            long snapshotTimestampMs,
            Map<String, UpstreamState> upstreamStates) {
        this.connected = connected;
        this.snapshotReplay = snapshotReplay;
        this.snapshotTimestampMs = snapshotTimestampMs;
        this.upstreamStates = Collections.unmodifiableMap(upstreamStates);
    }

    static DiscoveryStatus empty() {
        return EMPTY;
    }

    /** 订阅流已建立（收到确认）。 */
    public boolean isConnected() {
        return connected;
    }

    /** 本订阅启动后尚未成功同步——数据来自磁盘快照（陈旧换可用，供消费方感知降级）。 */
    public boolean isSnapshotReplay() {
        return snapshotReplay;
    }

    /** 快照写入时间（epoch 毫秒）；0 = 无快照。 */
    public long snapshotTimestampMs() {
        return snapshotTimestampMs;
    }

    /** cluster → 最近一次拉取（Query/Checksum）的上游新鲜度；拉取前为空。 */
    public Map<String, UpstreamState> upstreamStates() {
        return upstreamStates;
    }
}
