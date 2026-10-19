package org.mydotey.ai.artemis.management.domain;

import java.util.Map;

/**
 * registry 返回的实例事实（只读视图）。
 */
public record Instance(
        String instanceId, String host, int port, InstanceStatus status, int weight, Map<String, String> metadata) {

    public Instance {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
