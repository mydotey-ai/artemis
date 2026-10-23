package org.mydotey.ai.artemis.discovery.domain;

/** 投影变更事件类型（消费方语义三值，projection.v1 契约同构；未知值收敛 UNKNOWN）。 */
public enum ChangeType {
    NEW,
    UPDATE,
    DELETE,
    UNKNOWN
}
