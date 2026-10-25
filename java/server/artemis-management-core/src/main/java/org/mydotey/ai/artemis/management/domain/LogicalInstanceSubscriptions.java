package org.mydotey.ai.artemis.management.domain;

/**
 * 逻辑实例订阅管理端口（管理面 → discovery 逻辑实例流）。实现由 infrastructure 提供
 * （{@code LogicalInstanceDispatcher}）。
 */
public interface LogicalInstanceSubscriptions {

    /** 登记订阅（登记即推快照）。 */
    void subscribe(LogicalInstanceSubscriber subscriber);

    /** 注销订阅（客户端正常取消；断流注销由实现内部处理）。 */
    void unsubscribe(LogicalInstanceSubscriber subscriber);
}
