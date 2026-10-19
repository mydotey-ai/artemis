package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseDetail;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStats;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryEjectionStore;

class ManagementServiceImplTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final FakeRegistry registry = new FakeRegistry();
    private final InMemoryEjectionStore ejections = new InMemoryEjectionStore();
    private final ManagementServiceImpl service =
            new ManagementServiceImpl(registry, ejections, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void should_filter_ejected_instances_when_get_service() {
        registry.instances.put(SERVICE, List.of(instance("i1"), instance("i2")));
        service.eject(SERVICE, "i1", "maintenance");

        ServiceDetail detail = service.getService(SERVICE, false);

        assertThat(detail.instanceCount()).isEqualTo(2);
        assertThat(detail.ejectedCount()).isEqualTo(1);
        assertThat(detail.effectiveInstanceCount()).isEqualTo(1);
        assertThat(detail.instances())
                .singleElement()
                .extracting(InstanceView::instanceId)
                .isEqualTo("i2");
    }

    @Test
    void should_include_ejected_instances_when_flag_set() {
        registry.instances.put(SERVICE, List.of(instance("i1"), instance("i2")));
        service.eject(SERVICE, "i1", "maintenance");

        ServiceDetail detail = service.getService(SERVICE, true);

        assertThat(detail.instances()).hasSize(2);
        assertThat(detail.instances().get(0).ejected()).isTrue();
        assertThat(detail.instances().get(0).reason()).isEqualTo("maintenance");
        assertThat(detail.instances().get(0).ejectedAt()).isEqualTo(NOW);
        assertThat(detail.instances().get(1).ejected()).isFalse();
    }

    @Test
    void should_return_zero_counts_when_service_not_registered() {
        ServiceDetail detail = service.getService(SERVICE, false);

        assertThat(detail.instanceCount()).isZero();
        assertThat(detail.ejectedCount()).isZero();
        assertThat(detail.effectiveInstanceCount()).isZero();
        assertThat(detail.instances()).isEmpty();
    }

    @Test
    void should_count_only_registered_ejections_when_get_service() {
        registry.instances.put(SERVICE, List.of(instance("i1")));
        service.eject(SERVICE, "i1", "r1");
        service.eject(SERVICE, "stale", "r2");

        ServiceDetail detail = service.getService(SERVICE, false);

        assertThat(detail.ejectedCount()).isEqualTo(1);
        assertThat(detail.effectiveInstanceCount()).isZero();
    }

    @Test
    void should_list_services_with_fact_and_ejection_counts() {
        registry.services.add(new ServiceSummary(SERVICE, 2));
        registry.services.add(new ServiceSummary(new ServiceKey("ns", "another"), 1));
        service.eject(SERVICE, "i1", "r1");

        List<ServiceView> views = service.listServices("ns");

        assertThat(views)
                .extracting(ServiceView::key, ServiceView::instanceCount, ServiceView::ejectedCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(new ServiceKey("ns", "another"), 1, 0),
                        org.assertj.core.groups.Tuple.tuple(SERVICE, 2, 1));
    }

    @Test
    void should_be_idempotent_when_ejecting_twice() {
        EjectionView first = service.eject(SERVICE, "i1", "first");
        EjectionView second = service.eject(SERVICE, "i1", "second");

        assertThat(second).isEqualTo(first);
        assertThat(second.reason()).isEqualTo("first");
        assertThat(second.ejectedAt()).isEqualTo(NOW);
    }

    @Test
    void should_restore_instance_when_recovered() {
        registry.instances.put(SERVICE, List.of(instance("i1")));
        service.eject(SERVICE, "i1", "r");
        service.recover(SERVICE, "i1");

        assertThat(service.getService(SERVICE, false).instances()).hasSize(1);
    }

    @Test
    void should_be_idempotent_when_recovering_not_ejected() {
        registry.instances.put(SERVICE, List.of(instance("i1")));

        service.recover(SERVICE, "i1");
        service.recover(SERVICE, "i1");

        assertThat(service.getService(SERVICE, false).instances()).hasSize(1);
    }

    @Test
    void should_not_touch_registry_when_ejecting_or_recovering() {
        int callsBefore = registry.calls;

        service.eject(SERVICE, "i1", "r");
        service.recover(SERVICE, "i1");

        assertThat(registry.calls).isEqualTo(callsBefore);
    }

    @Test
    void should_passthrough_lease_status_when_requested() {
        LeaseStatus status = new LeaseStatus(
                new SelfProtectionStats(5, 3, false), List.of(new LeaseDetail(SERVICE, "i1", 100, 900)), false);
        registry.leaseStatus = status;

        assertThat(service.leaseStatus("ns", "svc")).isSameAs(status);
    }

    @Test
    void should_passthrough_config_status_when_requested() {
        List<ConfigProperty> properties = List.of(new ConfigProperty("k", "v", "src", true, "d", "doc"));
        registry.properties = properties;

        assertThat(service.configStatus()).isSameAs(properties);
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static final class FakeRegistry implements RegistryQueryPort {

        private final List<ServiceSummary> services = new ArrayList<>();
        private final Map<ServiceKey, List<Instance>> instances = new HashMap<>();
        private LeaseStatus leaseStatus = new LeaseStatus(new SelfProtectionStats(0, 0, false), List.of(), false);
        private List<ConfigProperty> properties = List.of();
        private int calls;

        @Override
        public List<ServiceSummary> listServices(String namespace) {
            calls++;
            return services;
        }

        @Override
        public List<Instance> getInstances(ServiceKey key) {
            calls++;
            return instances.getOrDefault(key, List.of());
        }

        @Override
        public LeaseStatus leaseStatus(String namespace, String service) {
            calls++;
            return leaseStatus;
        }

        @Override
        public List<ConfigProperty> configStatus() {
            calls++;
            return properties;
        }
    }
}
