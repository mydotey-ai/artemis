package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** deregister 响应数据：失败明细平铺（与请求结构对齐）。 */
public record DeregisterResponseData(List<InstanceError> instances) {

    public DeregisterResponseData {
        instances = List.copyOf(instances);
    }
}
