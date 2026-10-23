package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 单服务实例集：投影存储与查询/推送的原子单元（ADR-007）；不可变。 */
public record ServiceInstances(ServiceKey key, List<Instance> instances) {

    public ServiceInstances {
        instances = List.copyOf(instances);
    }
}
