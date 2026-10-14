package org.mydotey.ai.artemis.registry.domain;

import java.util.Map;

/** 服务实例：客户端上报、服务端只存事实；不可变。 */
public record Instance(
        String instanceId, String host, int port, InstanceStatus status, int weight, Map<String, String> metadata) {

    public Instance {
        metadata = Map.copyOf(metadata);
    }
}
