package org.mydotey.ai.artemis.registry.domain;

/** 操作整体判定：任一失败即 FAIL（失败明细经 data 携带）。 */
public enum Ack {
    SUCCESS,
    FAIL
}
