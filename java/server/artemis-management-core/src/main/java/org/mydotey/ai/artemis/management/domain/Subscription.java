package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * discovery 活跃订阅条目（调用方视角，v0.5 phase-5 透传组合）：字段语义见
 * discovery 发现管理 API §3——subscriber 为 gRPC 流远端地址（传输层调用方标识，
 * 账户体系接管后升级）；services/clusters 空集合 = 全部语义同 discovery 选择器。
 */
public record Subscription(
        long id,
        String subscriber,
        Instant establishedAt,
        List<ServiceKey> services,
        Map<String, String> labels,
        List<String> clusters) {

    public Subscription {
        services = services == null ? List.of() : List.copyOf(services);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
    }
}
