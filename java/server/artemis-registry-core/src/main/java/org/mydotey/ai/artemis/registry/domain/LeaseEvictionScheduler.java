package org.mydotey.ai.artemis.registry.domain;

/**
 * 租约过期清理调度端口：周期性触发过期清理，维持「过期实例不可见」的领域不变量。
 * 线程模型与调度机制是实现细节（见 infrastructure），由装配层注入清理入口并组合。
 */
public interface LeaseEvictionScheduler extends AutoCloseable {

    /** 启动周期清理调度。 */
    void start();

    /** 停止调度并释放资源。 */
    @Override
    void close();
}
