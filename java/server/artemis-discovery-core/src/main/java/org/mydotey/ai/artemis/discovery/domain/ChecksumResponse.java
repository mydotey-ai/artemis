package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 对账应答：per-cluster checksum 表（缺项 = 视图无实例，D-F1）+ 新鲜度元信息（D-F4）。 */
public record ChecksumResponse(List<ClusterChecksums> clusters, List<UpstreamStatus> upstreams) {

    public ChecksumResponse {
        clusters = List.copyOf(clusters);
        upstreams = List.copyOf(upstreams);
    }
}
