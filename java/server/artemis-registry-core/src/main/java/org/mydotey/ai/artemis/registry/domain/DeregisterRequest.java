package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** deregister 请求：实例键平铺（意图语义，幂等）。 */
public record DeregisterRequest(List<InstanceKey> instances) {

    public DeregisterRequest {
        instances = List.copyOf(instances);
    }
}
