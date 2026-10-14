package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** register 响应数据：失败明细按 service 分组（仅失败项）。 */
public record RegisterResponseData(List<ServiceInstanceErrors> services) {

    public RegisterResponseData {
        services = List.copyOf(services);
    }
}
