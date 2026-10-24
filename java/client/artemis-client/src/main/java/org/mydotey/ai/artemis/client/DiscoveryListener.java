package org.mydotey.ai.artemis.client;

/**
 * 服务发现变更监听：本地视图可能已变化时触发（信号语义，无变更明细）。
 *
 * <p>回调在 SDK 专属有界线程执行（满丢最旧 + 计数，不阻塞推送）；消费方在回调内读
 * {@link Subscription#getInstances(ServiceId)} 取最新不可变视图（零拷贝）。异常 catch 记 WARN，不影响其它回调。
 */
public interface DiscoveryListener {

    void onServiceChanged(ServiceId service);
}
