package org.mydotey.ai.artemis.discovery.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ServiceDetail;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.ServiceView;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionInfo;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatus;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;
import org.mydotey.ai.artemis.discovery.domain.UpstreamsView;

/**
 * 发现管理面只读用例（D-A1–A6）：订阅关系 / 服务视图 / 上游与规则源状态。服务视图经
 * 过滤链构成（有效发现视图，D-A6，与查询/推送/对账同口径）；列表为全量扫描 + 聚合
 * （管理面低频，ponytail: 10 万服务级全量响应，console 分页随 v0.4 补齐）。
 */
public final class DiscoveryAdminService {

    private static final Comparator<ServiceDetail.ServiceClusterInstances> BY_CLUSTER =
            Comparator.comparing(ServiceDetail.ServiceClusterInstances::cluster);
    private static final Comparator<Instance> BY_INSTANCE_ID = Comparator.comparing(Instance::instanceId);

    private final SubscriptionManager subscriptions;
    private final ProjectionRegistry registry;
    private final DiscoveryFilterChain chain;
    private final UpstreamStatuses upstreamStatuses;
    private final ManagementStatus managementStatus;

    public DiscoveryAdminService(
            SubscriptionManager subscriptions,
            ProjectionRegistry registry,
            DiscoveryFilterChain chain,
            UpstreamStatuses upstreamStatuses,
            ManagementStatus managementStatus) {
        this.subscriptions = subscriptions;
        this.registry = registry;
        this.chain = chain;
        this.upstreamStatuses = upstreamStatuses;
        this.managementStatus = managementStatus;
    }

    /** 活跃订阅快照（调用方视角，D-A3）。 */
    public List<SubscriptionInfo> subscriptions() {
        return subscriptions.snapshot();
    }

    /** 服务视图列表（D-A6）：namespace/cluster 空白 = 全部；按 (cluster, namespace, service) 升序。 */
    public List<ServiceView> services(String namespace, String cluster) {
        DiscoverySelector selector =
                new DiscoverySelector(Set.of(), Map.of(), isBlank(cluster) ? Set.of() : Set.of(cluster));
        List<ServiceView> views = new ArrayList<>();
        for (ClusterServiceInstances entry : registry.query(selector, chain)) {
            ServiceKey key = entry.instances().key();
            if (isBlank(namespace) || namespace.equals(key.namespace())) {
                views.add(new ServiceView(
                        entry.cluster(),
                        key.namespace(),
                        key.service(),
                        entry.instances().instances().size()));
            }
        }
        views.sort(Comparator.comparing(ServiceView::cluster)
                .thenComparing(ServiceView::namespace)
                .thenComparing(ServiceView::service));
        return List.copyOf(views);
    }

    /** 服务详情（D-A6）：未命中/全部被过滤 = 空 clusters；cluster 与实例均稳定排序。 */
    public ServiceDetail service(ServiceKey key) {
        requireNonBlank(key == null ? null : key.namespace(), "namespace");
        requireNonBlank(key.service(), "service");
        DiscoverySelector selector = new DiscoverySelector(Set.of(key), Map.of(), Set.of());
        List<ServiceDetail.ServiceClusterInstances> clusters = new ArrayList<>();
        for (ClusterServiceInstances entry : registry.query(selector, chain)) {
            List<Instance> instances = new ArrayList<>(entry.instances().instances());
            if (instances.isEmpty()) {
                continue; // 显式选择器可命中过滤后空条目；管理详情只出可见实例（D-A6）
            }
            instances.sort(BY_INSTANCE_ID);
            clusters.add(new ServiceDetail.ServiceClusterInstances(entry.cluster(), instances));
        }
        clusters.sort(BY_CLUSTER);
        return new ServiceDetail(key.namespace(), key.service(), clusters);
    }

    /** 上游状态/新鲜度 + 规则源状态（D-A4/D-A5）：按 cluster 升序，management 未配置 = DISABLED。 */
    public UpstreamsView upstreams() {
        List<UpstreamStatus> upstreams = new ArrayList<>(upstreamStatuses.snapshot());
        upstreams.sort(Comparator.comparing(UpstreamStatus::cluster));
        ManagementState management = managementStatus.state();
        return new UpstreamsView(upstreams, management != ManagementState.DISABLED, management);
    }

    private static void requireNonBlank(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
