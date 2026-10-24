package org.mydotey.ai.artemis.discovery.application;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 就绪门（纯 JDK，phase-5 多上游）：**全部**配置上游完成首轮同步（bootstrap 覆盖落地）
 * 前不接查询流量——任一上游缺席即 union 不完整，空数据不作真相（资产 #9）。只升不降：
 * 上游断连后继续服务投影（陈旧换可用，D-D5），新鲜度出口归 phase-4。停机排水语义随后续
 * 版本按需演进。
 */
public final class DiscoveryReadinessGate {

    private final int expectedUpstreams;
    private final Set<String> syncedClusters = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    public DiscoveryReadinessGate(int expectedUpstreams) {
        if (expectedUpstreams < 1) {
            throw new IllegalArgumentException("expectedUpstreams must be >= 1: " + expectedUpstreams);
        }
        this.expectedUpstreams = expectedUpstreams;
    }

    public boolean isReady() {
        return ready;
    }

    /** 上游首轮 SYNCED 上报（每同步器一次，cluster 去重累计）；全部到齐翻转 ready。 */
    public void markSynced(String cluster) {
        Objects.requireNonNull(cluster, "cluster");
        syncedClusters.add(cluster);
        if (syncedClusters.size() >= expectedUpstreams) {
            ready = true;
        }
    }
}
