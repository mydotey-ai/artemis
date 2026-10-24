package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 管理面服务详情（D-A6）：逐来源 cluster 的可见实例；未命中时 clusters 为空。 */
public record ServiceDetail(String namespace, String service, List<ServiceClusterInstances> clusters) {

    public ServiceDetail {
        clusters = List.copyOf(clusters);
    }

    /** 单来源 cluster 的可见实例集。 */
    public record ServiceClusterInstances(String cluster, List<Instance> instances) {

        public ServiceClusterInstances {
            instances = List.copyOf(instances);
        }
    }
}
