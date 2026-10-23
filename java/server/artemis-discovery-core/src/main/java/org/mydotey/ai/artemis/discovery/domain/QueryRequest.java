package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;
import java.util.Map;

/** 查询请求（契约原样入参：services 可能重复，归一在应用服务完成）。 */
public record QueryRequest(List<ServiceKey> services, Map<String, String> labels) {

    public QueryRequest {
        services = List.copyOf(services);
        labels = Map.copyOf(labels);
    }
}
