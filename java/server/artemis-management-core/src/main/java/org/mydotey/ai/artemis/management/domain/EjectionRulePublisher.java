package org.mydotey.ai.artemis.management.domain;

/**
 * 摘除规则变更发布端口（application 调用）：摘除/恢复落库后通知规则流分发——
 * 仅目标生效态翻转时（叠加/对消无翻转不推）。分发为 at-most-once（失败不影响
 * 管理操作，断流重连快照自愈）。phase-5：事件携带规则集状态版本（翻转记录
 * sequence，水位语义）。
 */
public interface EjectionRulePublisher {

    void onEjected(EjectionRecord record);

    void onRecovered(EjectionTarget target, long version);
}
