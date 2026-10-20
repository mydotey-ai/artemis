package org.mydotey.ai.artemis.registry.domain;

/**
 * 集群端点（host + 端口）。
 *
 * @param host 主机名或 IP（IPv6 不含方括号）
 * @param port 端口
 */
public record ClusterEndpoint(String host, int port) {

    public ClusterEndpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("cluster endpoint host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("cluster endpoint port out of range: " + host + ":" + port);
        }
    }
}
