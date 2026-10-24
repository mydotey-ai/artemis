package org.mydotey.ai.artemis.discovery.domain;

import java.util.Map;
import java.util.Set;

/**
 * 发现选择器（查询与订阅共用模型，v0.3 plan）：
 * services 空集 = 全部服务；非空 = 精确 key 集合。
 * labels = 实例 metadata 全键值匹配（实例级子集过滤，分组语义 ADR-013）。
 * clusters 空集 = 全部来源 cluster（union，phase-5）；非空 = 精确集合，
 * 未知 cluster 名静默无命中（cluster 清单是服务端部署拓扑，不耦合 SDK）。
 */
public record DiscoverySelector(Set<ServiceKey> services, Map<String, String> labels, Set<String> clusters) {

    public DiscoverySelector {
        services = Set.copyOf(services);
        labels = Map.copyOf(labels);
        clusters = Set.copyOf(clusters);
    }

    public DiscoverySelector(Set<ServiceKey> services, Map<String, String> labels) {
        this(services, labels, Set.of());
    }

    /** 来源 cluster 命中判定：clusters 空集恒真（union 默认）。 */
    public boolean matchesCluster(String cluster) {
        return clusters.isEmpty() || clusters.contains(cluster);
    }

    /** 信任边界校验（查询/订阅共用，一次完成）：显式 services/clusters 字段空白非法。 */
    public void requireValid() {
        for (ServiceKey key : services) {
            if (isBlank(key.namespace()) || isBlank(key.service())) {
                throw new IllegalArgumentException(
                        "blank namespace/service in discovery selector: " + key.namespace() + "/" + key.service());
            }
        }
        for (String cluster : clusters) {
            if (isBlank(cluster)) {
                throw new IllegalArgumentException("blank cluster in discovery selector: " + cluster);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
