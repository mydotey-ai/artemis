package org.mydotey.ai.artemis.discovery.domain;

/** 上游同步状态（phase-2 内部维护：日志/排障；新鲜度出口 phase-4、管理面出口 phase-7）。 */
public enum UpstreamState {
    /** 连接与首轮 bootstrap 进行中。 */
    SYNCING,
    /** 已完成首轮同步，正在消费变更流。 */
    SYNCED,
    /** 已同步过但当前流断开，等待重连（投影继续服务，D-D5）。 */
    DISCONNECTED
}
