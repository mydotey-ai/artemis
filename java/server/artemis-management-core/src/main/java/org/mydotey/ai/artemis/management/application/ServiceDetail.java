package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 服务详情：事实计数、注册实例中被摘除数与有效数；instances 默认只含有效实例。
 */
public record ServiceDetail(
        ServiceKey key, int instanceCount, int ejectedCount, int effectiveInstanceCount, List<InstanceView> instances) {

    public ServiceDetail {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }
}
