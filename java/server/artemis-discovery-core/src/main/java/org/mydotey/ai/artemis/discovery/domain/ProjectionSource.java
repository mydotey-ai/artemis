package org.mydotey.ai.artemis.discovery.domain;

/**
 * 上游投影数据源端口（出站，server 层提供 gRPC 实现）。
 *
 * <p>会话模型：一轮同步（Watch 建立确认 → Bootstrap → 变更消费）的 watch 与 bootstrap
 * <b>必须绑定同一上游端点</b>——跨端点是注册表的不同副本视图（复制时差），混用会使
 * bootstrap 快照与变更流失去同源全序、覆盖吞变更（D-D3 论文的前提）。端点选择与轮换
 * （每轮依次尝试全部配置端点）由实现方在 {@link #open} 内完成，对调用方不可见。
 */
public interface ProjectionSource {

    /** 开启一轮同步会话（绑定单个上游端点）；失败重开即换端点。 */
    ProjectionSession open(String requesterId);
}
