package org.mydotey.ai.artemis.registry.domain;

/**
 * 变更事件类型（registry 内部统一事件源）。
 *
 * <p>各消费通道在边界收敛为消费方语义：复制扇出过滤 {@link #EVICTED}（删除不跨节点传播），
 * 投影分发过滤 {@link #RENEW}（内容未变不推送）、把 {@link #EVICTED} 映射为 {@link #DELETE}
 * （清理方式是 registry 内部概念，不进分发契约）。
 */
public enum ChangeType {
    NEW,
    UPDATE,
    DELETE,
    RENEW,
    /** 租约 TTL 过期清理产出（registry 内部事件类型）。 */
    EVICTED
}
