package org.mydotey.ai.artemis.client.internal;

/** 快照落盘源（内部端口）：writer 合并写请求，调用于单线程 writer。 */
interface SnapshotSource {

    void writeSnapshot();
}
