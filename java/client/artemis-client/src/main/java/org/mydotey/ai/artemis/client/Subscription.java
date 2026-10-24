package org.mydotey.ai.artemis.client;

import java.util.List;

/**
 * 单个选择器的发现订阅（{@link DiscoveryClient#subscribe} 产物）：
 * 独立的订阅流、本地视图、对账/刷新调度与磁盘快照。
 *
 * <p>生命周期：{@code subscribe} 即生效（建立流并开始同步）；{@link #close()} 幂等——
 * cancel 流、停本订阅周期任务并终写快照；close 后调用 {@link #getInstances}/{@link #status}
 * 抛 {@link IllegalStateException}（编程错误，与注册侧一致）。
 */
public interface Subscription extends AutoCloseable {

    /** 本订阅的选择器。 */
    DiscoverySelector selector();

    /**
     * 本地视图（来源 cluster 的 union）：不可变列表引用，读路径零拷贝；无数据返回空列表。
     * 变更推送/全量刷新异步应用，调用方读到的是已应用快照（最终一致）。
     */
    List<ServiceInstance> getInstances(ServiceId service);

    /** 当前状态（连接态 / 快照回放 / 最近拉取的上游新鲜度）。 */
    DiscoveryStatus status();

    @Override
    void close();
}
