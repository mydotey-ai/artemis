package org.mydotey.ai.artemis.discovery.domain;

import java.util.Map;
import java.util.Set;

/**
 * 发现选择器（查询与订阅共用模型，v0.3 plan）：
 * services 空集 = 全部服务；非空 = 精确 key 集合。
 * labels = 实例 metadata 全键值匹配（实例级子集过滤，分组语义 ADR-013）。
 */
public record DiscoverySelector(Set<ServiceKey> services, Map<String, String> labels) {

    public DiscoverySelector {
        services = Set.copyOf(services);
        labels = Map.copyOf(labels);
    }
}
