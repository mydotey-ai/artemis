package org.mydotey.ai.artemis.management.domain;

/**
 * 逻辑实例变更发布端口（application 调用）：变更落库后通知流分发（内容变化/命中删除
 * 才推）。分发为 at-most-once（失败不影响管理操作，断流重连快照自愈）。
 */
public interface LogicalInstancePublisher {

    void onUpserted(LogicalInstance instance);

    void onDeleted(ServiceKey service, String instanceId);
}
