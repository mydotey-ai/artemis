package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 增量复制批量（契约 {@code ReplicationBatch} 的领域形态）：事件产生序保持。
 *
 * <p>入站方向 {@code changes} 的 changeTimeMs 无意义（源节点单调钟不可跨进程，D14），固定 0。
 *
 * @param sourceNodeId 事件的产生节点（= 事实源节点；零事件不变量保证副本节点不产生事件）
 */
public record ReplicationBatch(String sourceNodeId, List<InstanceChange> changes) {

    public ReplicationBatch {
        changes = List.copyOf(changes);
    }
}
