package org.mydotey.ai.artemis.discovery.server.upstream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.common.tls.TlsClientSettings;
import org.mydotey.ai.artemis.common.tls.TlsSettings;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 上游 registry 集群配置（静态，D-D8）：JSON map 形态，键 = clusterId、值 = 逗号分隔
 * 端点列表（phase-5 多集群）。至少一个集群——上游 1..N 是 discovery 存在前提，
 * 未配置/非法 fail-fast 拒绝启动（converter 对非法 JSON 返回 null，校验收敛到此构造器）。
 *
 * <p>集群集合启动定死（对齐 registry 静态成员哲学）；上游认证全局共享。
 */
public final class UpstreamConfig {

    static final String KEY_CLUSTERS = "artemis.discovery.registry.clusters";
    static final String KEY_TLS_PREFIX = "artemis.discovery.registry.tls";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Class<Map<String, List<String>>> CLUSTERS_TYPE = (Class) Map.class; // 泛型擦除

    private final List<Upstream> upstreams;
    private final TlsClientSettings tls;

    public UpstreamConfig(StringProperties properties) {
        this.tls = TlsSettings.client(properties, KEY_TLS_PREFIX);
        Property<String, Map<String, List<String>>> clusters = ScfProperties.staticProperty(
                properties,
                KEY_CLUSTERS,
                CLUSTERS_TYPE,
                Map.of(),
                StringToJsonListMapConverter.DEFAULT,
                null,
                "upstream registry clusters, json map of clusterId to comma-separated endpoints (static)");
        Map<String, List<String>> value = clusters.getValue();
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "missing required config " + KEY_CLUSTERS + " (json map: {\"cluster\":\"host:port,...\"})");
        }
        this.upstreams = parse(value);
    }

    public List<Upstream> upstreams() {
        return upstreams;
    }

    /** 出站 TLS（静态）：投影订阅/bootstrap channel 装配。 */
    public TlsClientSettings tls() {
        return tls;
    }

    private List<Upstream> parse(Map<String, List<String>> clusters) {
        List<Upstream> result = new ArrayList<>(clusters.size());
        for (Map.Entry<String, List<String>> entry : clusters.entrySet()) {
            String clusterId = entry.getKey();
            if (clusterId == null || clusterId.isBlank()) {
                throw new IllegalStateException("blank cluster id in " + KEY_CLUSTERS + ": " + clusterId);
            }
            List<Endpoint> endpoints = new ArrayList<>();
            for (String raw : entry.getValue()) {
                if (raw == null) {
                    continue;
                }
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                endpoints.add(endpoint(trimmed));
            }
            if (endpoints.isEmpty()) {
                throw new IllegalStateException(
                        "no valid endpoints for cluster '" + clusterId + "' in " + KEY_CLUSTERS);
            }
            result.add(new Upstream(clusterId, List.copyOf(endpoints)));
        }
        return List.copyOf(result);
    }

    /** 端点解析（host:port；节点目录配置复用，ADR-020）。 */
    static Endpoint endpoint(String trimmed) {
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
            throw new IllegalStateException("invalid upstream endpoint port '" + portText + "': " + trimmed);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("invalid upstream endpoint port '" + portText + "' (1..65535): " + trimmed);
        }
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("invalid upstream endpoint host: " + trimmed);
        }
        // 裸 IPv6 含多个冒号无法区分 host/port，必须用括号；括号必须成对
        boolean bracketed = host.startsWith("[") && host.endsWith("]");
        if (host.startsWith("[") != host.endsWith("]") || (host.contains(":") && !bracketed)) {
            throw new IllegalStateException("invalid upstream endpoint host (IPv6 must be bracketed): " + trimmed);
        }
        return new Endpoint(host, port);
    }

    /** 单个上游集群：clusterId + 该集群端点列表（同步器端点轮换的候选范围）。 */
    record Upstream(String clusterId, List<Endpoint> endpoints) {}

    /** 上游端点（host 不带方括号；IPv6 字面量同 peer 成员表处理约定）。 */
    public record Endpoint(String host, int port) {}
}
