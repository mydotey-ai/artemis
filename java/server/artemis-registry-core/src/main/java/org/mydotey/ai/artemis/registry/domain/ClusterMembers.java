package org.mydotey.ai.artemis.registry.domain;

import java.util.List;
import java.util.Optional;

/**
 * 端口：集群成员来源（架构 §4.1 成员抽象）。v0.2 静态配置实现：成员表 + 显式自身识别；
 * 动态成员（K8s 成员源）后置版本。
 */
public interface ClusterMembers {

    List<ClusterMember> members();

    /** 本节点在成员表中的身份；单节点模式（成员表为空）为空。 */
    Optional<ClusterMember> self();
}
