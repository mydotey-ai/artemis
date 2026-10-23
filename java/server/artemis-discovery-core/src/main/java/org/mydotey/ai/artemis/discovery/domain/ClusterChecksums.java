package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 单 cluster 的对账表分片（cluster 来源标识，ADR-014 投影边界注入）；不可变。 */
public record ClusterChecksums(String cluster, List<ServiceChecksum> services) {

    public ClusterChecksums {
        services = List.copyOf(services);
    }
}
