package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 查询应答：按 cluster 注入的投影视图列表。 */
public record QueryResponse(List<ClusterServiceInstances> services) {

    public QueryResponse {
        services = List.copyOf(services);
    }
}
