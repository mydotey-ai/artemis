package org.mydotey.ai.artemis.management.domain;

/**
 * 摘除规则订阅管理端口（管理面 → discovery 规则流，架构 §2.2 通道①）。
 * 实现由 infrastructure 提供（{@code EjectionRuleDispatcher}）。
 */
public interface EjectionRuleSubscriptions {

    /** 登记订阅（登记即推快照）。 */
    void subscribe(EjectionRuleSubscriber subscriber);

    /** 注销订阅（客户端正常取消；断流注销由实现内部处理）。 */
    void unsubscribe(EjectionRuleSubscriber subscriber);
}
