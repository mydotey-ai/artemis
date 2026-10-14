package org.mydotey.ai.artemis.registry.domain;

/**
 * 端口：变更事件出口（同步回调）。跨节点复制为 v0.2 语义。
 *
 * <p>回调契约（D13 锁内派发）：在聚合的 holder 锁内被调用，同 key 事件到达序 = 存储变更序——
 * 实现须非阻塞（不得做 IO/网络扇出，排队移交专属 executor）、不得跨 key 回调注册表（防锁交叉死锁；
 * 同 key 读安全，synchronized 可重入）；单事件抛出的异常被聚合隔离并降级为日志，实现须自行处理
 * 自身失败语义。
 */
@FunctionalInterface
public interface ChangeListener {

    void onChange(InstanceChange change);
}
