package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * discovery 只读发现管理查询出站端口（HTTP 实现由 server 模块提供，架构 §2.1/§2.2：
 * management → discovery 依赖 0..N 的首条只读通道，v0.5 phase-5）。
 */
public interface SubscriptionQueryPort {

    /** 逐源结果（源按配置序）；单源失败以 error 承载、不拖垮其余源。 */
    List<SubscriptionSource> subscriptions();
}
