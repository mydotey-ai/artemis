package org.mydotey.ai.artemis.discovery.server.upstream;

import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.facade.StringProperties;

/**
 * management 上游配置（静态，0..1，D-E4）：逗号分隔端点列表 + 通道共享接收上限
 * （规则流与逻辑实例流同 key 单点定义，guidelines §10.1 共享配置纪律）。
 * 未配置端点 = 不启用过滤与逻辑源（空链直通零开销）。management 单节点，端点列表容忍其重启换址。
 * 值非空但端点非法 fail-fast 拒绝启动。
 */
public final class ManagementConfig {

    static final String KEY_ENDPOINTS = "artemis.discovery.management.endpoints";
    static final String KEY_MAX_INBOUND_MESSAGE_BYTES = "artemis.discovery.management.maxInboundMessageBytes";

    private static final int DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final List<UpstreamConfig.Endpoint> endpoints;
    private final int maxInboundMessageBytes;

    public ManagementConfig(StringProperties properties) {
        String raw = properties.getStringProperty(KEY_ENDPOINTS, "").getValue();
        this.endpoints = parse(raw == null ? "" : raw);
        this.maxInboundMessageBytes = ScfProperties.staticInt(
                        properties,
                        KEY_MAX_INBOUND_MESSAGE_BYTES,
                        DEFAULT_MAX_INBOUND_MESSAGE_BYTES,
                        1024 * 1024,
                        64 * 1024 * 1024,
                        "max inbound message bytes for management channels (static)")
                .getValue();
    }

    /** 通道接收上限（规则流 + 逻辑实例流共享，单一 PropertyConfig）。 */
    public int maxInboundMessageBytes() {
        return maxInboundMessageBytes;
    }

    public boolean enabled() {
        return !endpoints.isEmpty();
    }

    public List<UpstreamConfig.Endpoint> endpoints() {
        return endpoints;
    }

    private static List<UpstreamConfig.Endpoint> parse(String raw) {
        if (raw.trim().isEmpty()) {
            return List.of(); // 未配置（纯空白视为未配置）：不启用（0..1）
        }
        List<UpstreamConfig.Endpoint> endpoints = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException("blank endpoint in " + KEY_ENDPOINTS + ": '" + raw + "'");
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalStateException("invalid management endpoint (expect host:port): " + trimmed);
            }
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid management endpoint port: " + trimmed);
            }
            endpoints.add(new UpstreamConfig.Endpoint(trimmed.substring(0, colon), port));
        }
        return List.copyOf(endpoints);
    }
}
