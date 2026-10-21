package org.mydotey.ai.artemis.management.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;

/**
 * 有效视图组合：查询 registry 事实，叠加内存摘除记录；摘除/恢复只写 management 存储，不触达 registry。
 */
public final class ManagementServiceImpl implements ManagementService {

    private static final Comparator<ServiceKey> SERVICE_ORDER =
            Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service);

    private final RegistryQueryPort registry;
    private final EjectionStore ejections;
    private final Clock clock;

    public ManagementServiceImpl(RegistryQueryPort registry, EjectionStore ejections, Clock clock) {
        this.registry = registry;
        this.ejections = ejections;
        this.clock = clock;
    }

    @Override
    public List<ServiceView> listServices(String namespace) {
        Map<ServiceKey, Long> ejectionCounts = ejections.countByService();
        return registry.listServices(namespace).stream()
                .sorted(Comparator.comparing(ServiceSummary::key, SERVICE_ORDER))
                .map(summary -> new ServiceView(
                        summary.key(),
                        summary.instanceCount(),
                        ejectionCounts.getOrDefault(summary.key(), 0L).intValue()))
                .toList();
    }

    @Override
    public ServiceDetail getService(ServiceKey key, boolean includeEjected) {
        List<Instance> instances = registry.getInstances(key);
        Map<String, EjectionRecord> byInstance = ejections.findByService(key).stream()
                .collect(Collectors.toMap(
                        record -> record.instance().instanceId(), Function.identity(), (first, second) -> first));

        List<InstanceView> views = instances.stream()
                .sorted(Comparator.comparing(Instance::instanceId))
                .map(instance -> InstanceView.of(instance, byInstance.get(instance.instanceId())))
                .toList();
        int ejectedCount = (int) views.stream().filter(InstanceView::ejected).count();
        List<InstanceView> visible = includeEjected
                ? views
                : views.stream().filter(view -> !view.ejected()).toList();

        return new ServiceDetail(key, instances.size(), ejectedCount, instances.size() - ejectedCount, visible);
    }

    @Override
    public EjectionView eject(ServiceKey key, String instanceId, String reason) {
        EjectionRecord stored =
                ejections.eject(new EjectionRecord(new InstanceKey(key, instanceId), reason, clock.instant()));
        return new EjectionView(
                key.namespace(), key.service(), stored.instance().instanceId(), stored.reason(), stored.ejectedAt());
    }

    @Override
    public void recover(ServiceKey key, String instanceId) {
        ejections.recover(new InstanceKey(key, instanceId));
    }

    @Override
    public LeaseStatus leaseStatus(String namespace, String service) {
        return registry.leaseStatus(namespace, service);
    }

    @Override
    public SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal) {
        return registry.selfProtectionStatus(namespace, service, includeNormal);
    }

    @Override
    public List<ConfigProperty> configStatus() {
        return registry.configStatus();
    }
}
