package org.mydotey.ai.artemis.discovery.server.upstream;

import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 上游 registry 配置（静态，D-D8）：单集群（多集群 phase-5 演进）。
 * endpoints 必填——上游 1..N 是 discovery 存在前提，未配置 fail-fast 拒绝启动。
 */
public final class UpstreamConfig {

    static final String KEY_CLUSTER_ID = "artemis.discovery.upstream.clusterId";
    static final String KEY_ENDPOINTS = "artemis.discovery.upstream.endpoints";

    private static final String DEFAULT_CLUSTER_ID = "default";

    private final String clusterId;
    private final List<Endpoint> endpoints;

    public UpstreamConfig(StringProperties properties) {
        Property<String, String> clusterId = ScfProperties.staticString(
                properties, KEY_CLUSTER_ID, DEFAULT_CLUSTER_ID, "upstream cluster identity (static)");
        this.clusterId = clusterId.getValue();
        Property<String, String> endpoints = ScfProperties.staticString(
                properties, KEY_ENDPOINTS, "", "upstream registry endpoints host:port, comma-separated (static)");
        this.endpoints = parse(endpoints.getValue());
        if (this.endpoints.isEmpty()) {
            throw new IllegalStateException(
                    "missing required config " + KEY_ENDPOINTS + " (host:port, comma-separated)");
        }
    }

    public String clusterId() {
        return clusterId;
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    private static List<Endpoint> parse(String value) {
        List<Endpoint> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalStateException("invalid upstream endpoint (expect host:port): " + trimmed);
            }
            String host = trimmed.substring(0, colon);
            String portText = trimmed.substring(colon + 1);
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "invalid upstream endpoint port '" + portText + "' in " + KEY_ENDPOINTS + ": " + trimmed);
            }
            result.add(new Endpoint(host, port));
        }
        return List.copyOf(result);
    }

    /** 上游端点（host 不带方括号；IPv6 字面量同 peer 成员表处理约定）。 */
    record Endpoint(String host, int port) {}
}
