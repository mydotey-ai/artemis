package org.mydotey.ai.artemis.discovery.domain;

/**
 * 发现视图过滤器（继承资产 #7，架构 §4.2）：投影事实读出后的视图构成时刻判定——
 * 查询/推送/对账三出口一致口径（D-E1）。实现须无锁可重入（热路径逐实例调用）；
 * 异常由 {@link DiscoveryFilterChain} 统一 fail-open。
 */
public interface DiscoveryFilter {

    /**
     * @return false = 实例不进发现视图。
     */
    boolean isVisible(ServiceKey service, Instance instance);
}
