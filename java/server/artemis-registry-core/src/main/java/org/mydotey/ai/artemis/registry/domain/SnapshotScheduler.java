package org.mydotey.ai.artemis.registry.domain;

/**
 * 快照落盘调度端口：周期性触发快照写（先例 {@link LeaseEvictionScheduler}）。
 * 线程模型与调度机制是实现细节（见 infrastructure），由装配层注入快照入口并组合。
 */
public interface SnapshotScheduler extends AutoCloseable {

    /** 启动周期快照调度。 */
    void start();

    /** 停止调度并释放资源。 */
    @Override
    void close();
}
