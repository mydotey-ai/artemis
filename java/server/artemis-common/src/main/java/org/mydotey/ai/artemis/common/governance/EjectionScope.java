package org.mydotey.ai.artemis.common.governance;

/**
 * 四级摘除目标范围（instance → server → zone → group，继承治理语义；ADR-013 延伸）。
 *
 * <p>SERVER/ZONE/GROUP 级摘除目标取值自实例 metadata 键（{@link #metadataKey()}），
 * 全局生效（不绑 service/namespace）——物理机/机房/组故障不分 namespace。
 * management 与 discovery 两侧共享（client 不依赖 common，无泄漏）。
 */
public enum EjectionScope {
    INSTANCE(null),
    SERVER("server"),
    ZONE("zone"),
    GROUP("group");

    private final String metadataKey;

    EjectionScope(String metadataKey) {
        this.metadataKey = metadataKey;
    }

    /** selector 级的实例 metadata 键；INSTANCE 级无（null）。 */
    public String metadataKey() {
        return metadataKey;
    }
}
