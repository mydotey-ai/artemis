package org.mydotey.ai.artemis.management.domain;

/**
 * 摘除规则变更发布端口（application 调用）：摘除/恢复落库后通知规则流分发。
 * 分发为 at-most-once（失败不影响管理操作，断流重连快照自愈）。
 */
public interface EjectionRulePublisher {

    void onEjected(EjectionRecord record);

    void onRecovered(InstanceKey instance);
}
