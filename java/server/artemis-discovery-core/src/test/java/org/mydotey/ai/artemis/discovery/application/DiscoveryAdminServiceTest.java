package org.mydotey.ai.artemis.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.EjectionFilter;
import org.mydotey.ai.artemis.discovery.domain.EjectionRule;
import org.mydotey.ai.artemis.discovery.domain.EjectionRules;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamState;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;

class DiscoveryAdminServiceTest {

    private static final String CLUSTER_A = "cluster-a";
    private static final String CLUSTER_B = "cluster-b";
    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("other", "pay-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();
    private final EjectionFilter ejectionFilter = new EjectionFilter();
    private final DiscoveryFilterChain chain = DiscoveryFilterChain.of(ejectionFilter);
    private final SubscriptionManager subscriptions = new SubscriptionManager(registry, chain);
    private final UpstreamStatuses upstreamStatuses = new UpstreamStatuses();
    private final ManagementStatus managementStatus = new ManagementStatus();
    private final DiscoveryAdminService service =
            new DiscoveryAdminService(subscriptions, registry, chain, upstreamStatuses, managementStatus);

    @Test
    void should_list_effective_service_view_when_instances_registered() {
        registry.replaceAll(
                CLUSTER_A, List.of(service(ORDER, instance("i1"), instance("i2")), service(PAY, instance("p1"))));
        registry.replaceAll(CLUSTER_B, List.of(service(ORDER, instance("i3"))));

        assertThat(service.services(null, null))
                .extracting(
                        org.mydotey.ai.artemis.discovery.domain.ServiceView::cluster,
                        org.mydotey.ai.artemis.discovery.domain.ServiceView::namespace,
                        org.mydotey.ai.artemis.discovery.domain.ServiceView::service,
                        org.mydotey.ai.artemis.discovery.domain.ServiceView::instanceCount)
                .containsExactly(
                        tuple(CLUSTER_A, "default", "order-service", 2),
                        tuple(CLUSTER_A, "other", "pay-service", 1),
                        tuple(CLUSTER_B, "default", "order-service", 1));
    }

    @Test
    void should_filter_service_list_by_namespace_and_cluster() {
        registry.replaceAll(CLUSTER_A, List.of(service(ORDER, instance("i1")), service(PAY, instance("p1"))));
        registry.replaceAll(CLUSTER_B, List.of(service(ORDER, instance("i3"))));

        assertThat(service.services("other", null))
                .singleElement()
                .satisfies(view -> assertThat(view.service()).isEqualTo("pay-service"));
        assertThat(service.services(null, CLUSTER_B)).singleElement().satisfies(view -> {
            assertThat(view.cluster()).isEqualTo(CLUSTER_B);
            assertThat(view.service()).isEqualTo("order-service");
        });
        assertThat(service.services(" ", CLUSTER_A)).hasSize(2); // 空白 = 全部
    }

    @Test
    void should_hide_ejected_instance_from_admin_views() {
        registry.replaceAll(CLUSTER_A, List.of(service(ORDER, instance("i1"), instance("i2"))));
        ejectionFilter.replace(EjectionRules.of(List.of(new EjectionRule(ORDER, "i1", "test"))));

        assertThat(service.services(null, null))
                .singleElement()
                .satisfies(view -> assertThat(view.instanceCount()).isEqualTo(1));
        assertThat(service.service(ORDER).clusters())
                .singleElement()
                .satisfies(cluster -> assertThat(cluster.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i2"));

        // 全部实例被过滤：详情不出空 cluster 条目
        ejectionFilter.replace(EjectionRules.of(
                List.of(new EjectionRule(ORDER, "i1", "test"), new EjectionRule(ORDER, "i2", "test"))));
        assertThat(service.service(ORDER).clusters()).isEmpty();
    }

    @Test
    void should_return_sorted_detail_when_service_exists() {
        registry.replaceAll(CLUSTER_B, List.of(service(ORDER, instance("i3"), instance("i1"))));
        registry.replaceAll(CLUSTER_A, List.of(service(ORDER, instance("i2"))));

        var detail = service.service(ORDER);

        assertThat(detail.namespace()).isEqualTo("default");
        assertThat(detail.service()).isEqualTo("order-service");
        assertThat(detail.clusters())
                .extracting(org.mydotey.ai.artemis.discovery.domain.ServiceDetail.ServiceClusterInstances::cluster)
                .containsExactly(CLUSTER_A, CLUSTER_B);
        assertThat(detail.clusters().get(1).instances())
                .extracting(Instance::instanceId)
                .containsExactly("i1", "i3");
    }

    @Test
    void should_return_empty_clusters_when_service_unknown() {
        assertThat(service.service(new ServiceKey("default", "unknown")).clusters())
                .isEmpty();
    }

    @Test
    void should_reject_blank_service_path_when_querying_detail() {
        assertThatThrownBy(() -> service.service(new ServiceKey(" ", "order-service")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.service(new ServiceKey("default", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_expose_sorted_upstreams_and_management_state() {
        upstreamStatuses.update(CLUSTER_B, UpstreamState.SYNCED);
        upstreamStatuses.update(CLUSTER_A, UpstreamState.DISCONNECTED);

        var view = service.upstreams();
        assertThat(view.upstreams())
                .extracting(org.mydotey.ai.artemis.discovery.domain.UpstreamStatus::cluster)
                .containsExactly(CLUSTER_A, CLUSTER_B);
        assertThat(view.managementEnabled()).isFalse();
        assertThat(view.managementState()).isEqualTo(ManagementState.DISABLED);

        managementStatus.update(ManagementState.SYNCED);
        assertThat(service.upstreams().managementEnabled()).isTrue();
        assertThat(service.upstreams().managementState()).isEqualTo(ManagementState.SYNCED);
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }
}
