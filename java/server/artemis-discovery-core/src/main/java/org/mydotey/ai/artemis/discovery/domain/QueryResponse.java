package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 查询应答：按 cluster 注入的投影视图列表 + 新鲜度元信息（D-F4：非 SYNCED = 可能陈旧）。 */
public record QueryResponse(List<ClusterServiceInstances> services, List<UpstreamStatus> upstreams) {

    public QueryResponse {
        services = List.copyOf(services);
        upstreams = List.copyOf(upstreams);
    }
}
