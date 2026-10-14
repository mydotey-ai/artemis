package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** 服务对齐的错误分组：与 ServiceInstances 请求结构对齐。 */
public record ServiceInstanceErrors(ServiceKey key, List<InstanceError> failed) {

    public ServiceInstanceErrors {
        failed = List.copyOf(failed);
    }
}
