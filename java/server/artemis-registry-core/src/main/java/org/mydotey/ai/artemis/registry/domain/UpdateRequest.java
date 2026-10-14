package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** update 请求：批量服务实例分组（幂等 upsert，全量覆盖非字段 patch）。 */
public record UpdateRequest(List<ServiceInstances> services) {

    public UpdateRequest {
        services = List.copyOf(services);
    }
}
