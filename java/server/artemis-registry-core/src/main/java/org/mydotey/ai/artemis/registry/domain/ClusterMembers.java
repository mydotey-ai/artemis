package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 端口：集群成员来源（架构 §4.1 成员抽象）。v0.1 仅静态配置实现、单节点起步；
 * v0.2 集群复制与 readiness 分支①消费。动态成员（K8s 成员源）后置版本。
 */
@FunctionalInterface
public interface ClusterMembers {

    List<ClusterMember> members();
}
