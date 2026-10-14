package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** update 响应数据：失败明细按 service 分组（仅失败项）。 */
public record UpdateResponseData(List<ServiceInstanceErrors> services) {

    public UpdateResponseData {
        services = List.copyOf(services);
    }
}
