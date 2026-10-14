package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** register 请求：批量服务实例分组（无条件覆盖）。 */
public record RegisterRequest(List<ServiceInstances> services) {

    public RegisterRequest {
        services = List.copyOf(services);
    }
}
