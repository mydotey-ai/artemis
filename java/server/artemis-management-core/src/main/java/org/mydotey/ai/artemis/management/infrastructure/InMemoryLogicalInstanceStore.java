package org.mydotey.ai.artemis.management.infrastructure;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceStore;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 逻辑实例内存实现（内存态，重启丢失；DB 态由 MyBatis 实现接管）。
 * 键 (ServiceKey, instanceId)——ServiceKey 为 record，等值语义天然成立。
 */
public final class InMemoryLogicalInstanceStore implements LogicalInstanceStore {

    private record Key(ServiceKey service, String instanceId) {}

    private final ConcurrentHashMap<Key, LogicalInstance> instances = new ConcurrentHashMap<>();

    @Override
    public LogicalInstance find(ServiceKey service, String instanceId) {
        return instances.get(new Key(service, instanceId));
    }

    @Override
    public List<LogicalInstance> findAll() {
        return List.copyOf(instances.values());
    }

    @Override
    public LogicalInstance upsert(LogicalInstance instance) {
        return instances.put(key(instance), instance);
    }

    @Override
    public LogicalInstance delete(ServiceKey service, String instanceId) {
        return instances.remove(new Key(service, instanceId));
    }

    private static Key key(LogicalInstance instance) {
        return new Key(instance.service(), instance.instance().instanceId());
    }
}
