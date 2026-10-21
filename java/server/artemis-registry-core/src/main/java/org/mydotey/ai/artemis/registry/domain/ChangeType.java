package org.mydotey.ai.artemis.registry.domain;

/**
 * 变更事件类型。
 *
 * <p>RENEW 为纯续约（心跳内容未变，phase-2 副本租约语义）：集群模式扇出维持 peer 副本租约，
 * 入站命中续约、缺席补注册重建。
 */
public enum ChangeType {
    NEW,
    UPDATE,
    DELETE,
    RENEW
}
