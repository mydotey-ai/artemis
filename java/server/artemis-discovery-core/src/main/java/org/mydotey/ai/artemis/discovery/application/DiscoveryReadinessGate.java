package org.mydotey.ai.artemis.discovery.application;

/**
 * 就绪门（纯 JDK）：首轮投影同步（bootstrap 覆盖落地）前不接查询流量——空数据不作真相
 * 返回（资产 #9）。只升不降：上游断连后继续服务投影（陈旧换可用，D-D5），新鲜度出口
 * 归 phase-4。停机排水语义随后续版本按需演进。
 */
public final class DiscoveryReadinessGate {

    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    public void markReady() {
        ready = true;
    }
}
