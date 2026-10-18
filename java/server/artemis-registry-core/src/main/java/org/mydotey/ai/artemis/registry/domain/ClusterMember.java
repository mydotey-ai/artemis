package org.mydotey.ai.artemis.registry.domain;

/**
 * 集群成员（成员抽象接口的静态实现元素，架构 §4.1）。
 *
 * @param id 成员标识（静态配置下取配置原文）
 * @param host 主机名或 IP
 * @param port 内部 peer 端口
 */
public record ClusterMember(String id, String host, int port) {

    public ClusterMember {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cluster member id must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("cluster member host must not be blank: " + id);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("cluster member port out of range: " + id);
        }
    }
}
