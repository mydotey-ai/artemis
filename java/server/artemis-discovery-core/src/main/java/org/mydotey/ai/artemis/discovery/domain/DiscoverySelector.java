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

    /** 信任边界校验（查询/订阅共用，一次完成）：显式 services 字段空白非法。 */
    public void requireExplicitServicesValid() {
        for (ServiceKey key : services) {
            if (isBlank(key.namespace()) || isBlank(key.service())) {
                throw new IllegalArgumentException(
                        "blank namespace/service in discovery selector: " + key.namespace() + "/" + key.service());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
