package org.mydotey.ai.artemis.discovery.server.upstream;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.type.string.StringToListConverter;

/**
 * 静态节点目录配置（ADR-020，SDK 前置寻址）：{@code host:port} 逗号分隔的部署声明——
 * 无节点 id、无 self 概念（地址即标识），各节点配置同一份、{@code /cluster/nodes} 原样返回。
 * 未配置 = 空目录（端点返回空列表）；非法端点/重复条目 fail-fast。
 */
public final class NodeDirectoryConfig {

    static final String KEY_NODES = "artemis.discovery.nodes";

    private final List<UpstreamConfig.Endpoint> nodes;

    @SuppressWarnings("unchecked")
    public NodeDirectoryConfig(StringProperties properties) {
        Property<String, List<String>> raw = ScfProperties.staticProperty(
                properties,
                KEY_NODES,
                (Class<List<String>>) (Class<?>) List.class,
                List.of(),
                StringToListConverter.DEFAULT,
                null,
                "static discovery node directory, comma separated host:port for sdk bootstrap (empty = directory off)");
        this.nodes = parse(raw.getValue());
    }

    /** 目录条目（host:port，去重保序）；未配置 = 空列表。 */
    public List<UpstreamConfig.Endpoint> nodes() {
        return nodes;
    }

    private static List<UpstreamConfig.Endpoint> parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<UpstreamConfig.Endpoint> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : entries) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            UpstreamConfig.Endpoint endpoint;
            try {
                endpoint = UpstreamConfig.endpoint(trimmed);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "invalid discovery node in " + KEY_NODES + " (expect host:port): " + trimmed, e);
            }
            if (!seen.add(trimmed)) {
                throw new IllegalStateException("duplicate discovery node in " + KEY_NODES + ": " + trimmed);
            }
            nodes.add(endpoint);
        }
        return List.copyOf(nodes);
    }
}
