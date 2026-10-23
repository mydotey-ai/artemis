package org.mydotey.ai.artemis.discovery.domain;

import java.util.Map;

/** 投影侧服务实例（消费 registry 事实，无租约概念）；不可变。 */
public record Instance(
        String instanceId, String host, int port, InstanceStatus status, int weight, Map<String, String> metadata) {

    public Instance {
        metadata = Map.copyOf(metadata);
    }
}
