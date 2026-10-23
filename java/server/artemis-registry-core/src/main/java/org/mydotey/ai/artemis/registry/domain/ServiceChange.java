package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** 单服务变更批：一个 key 下的变更保持源节点产生序。 */
public record ServiceChange(ServiceKey key, List<InstanceChange> changes) {

    public ServiceChange {
        changes = List.copyOf(changes);
    }
}
