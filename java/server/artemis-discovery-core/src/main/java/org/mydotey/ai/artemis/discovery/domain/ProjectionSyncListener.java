package org.mydotey.ai.artemis.discovery.domain;

/** 投影同步状态回调（同步器 → 装配层，readiness/新鲜度挂点）。 */
public interface ProjectionSyncListener {

    /** 首轮同步完成（bootstrap 覆盖落地）；只通知一次。 */
    void onSynced();

    /** 已同步后上游断连（readiness 不降，D-D5；新鲜度出口 phase-4 消费）。 */
    default void onDisconnected() {}
}
