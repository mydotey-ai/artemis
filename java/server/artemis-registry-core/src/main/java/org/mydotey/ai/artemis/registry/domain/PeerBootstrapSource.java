package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 端口：从 peer 拉取启动数据（状态查询 + 全量 bootstrap）。server 侧提供 gRPC 实现。
 * 两个方法均为阻塞语义，失败抛异常（由调用方决定重试/标记 UNKNOWN）。
 */
public interface PeerBootstrapSource {

    /** 查询 peer 自报状态；最多阻塞 timeoutMs。 */
    NodeStatus status(ClusterMember peer, long timeoutMs);

    /** 拉取 peer 全量注册表；阻塞至流结束。空注册表返回空列表。 */
    List<ServiceInstances> bootstrap(ClusterMember peer);
}
