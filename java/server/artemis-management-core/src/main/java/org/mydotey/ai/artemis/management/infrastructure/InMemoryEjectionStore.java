package org.mydotey.ai.artemis.management.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 摘除记录内存实现（v0.1，重启丢失；v0.4 由 DB 接管）。
 *
 * <p>ponytail: ConcurrentHashMap 原地写，管理操作低频；若摘除记录规模或读热路径增长，
 * 改为不可变快照 + volatile 发布（与注册表同模式）。
 */
public final class InMemoryEjectionStore implements EjectionStore {

    private static final Comparator<ServiceKey> SERVICE_ORDER =
            Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service);

    private final ConcurrentMap<InstanceKey, EjectionRecord> records = new ConcurrentHashMap<>();

    @Override
    public EjectionRecord eject(EjectionRecord record) {
        EjectionRecord existing = records.putIfAbsent(record.instance(), record);
        return existing == null ? record : existing;
    }

    @Override
    public boolean recover(InstanceKey instance) {
        return records.remove(instance) != null;
    }

    @Override
    public List<EjectionRecord> findByService(ServiceKey service) {
        return records.values().stream()
                .filter(record -> record.instance().service().equals(service))
                .sorted(Comparator.comparing(record -> record.instance().instanceId()))
                .toList();
    }

    @Override
    public Map<ServiceKey, Long> countByService() {
        return records.values().stream()
                .collect(Collectors.groupingBy(record -> record.instance().service(), Collectors.counting()));
    }

    @Override
    public List<EjectionRecord> findAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(
                                (EjectionRecord record) -> record.instance().service(), SERVICE_ORDER)
                        .thenComparing(record -> record.instance().instanceId()))
                .toList();
    }
}
