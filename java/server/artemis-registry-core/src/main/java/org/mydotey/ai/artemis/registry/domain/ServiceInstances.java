package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** 单服务实例集：上报与推送的原子单元；不可变。 */
public record ServiceInstances(ServiceKey key, List<Instance> instances) {

    public ServiceInstances {
        instances = List.copyOf(instances);
    }
}
