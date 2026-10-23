package org.mydotey.ai.artemis.discovery.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投影存储（D-D2）：per-cluster {@code ConcurrentHashMap<ServiceKey, ServiceInstances>}，
 * 值不可变、per-service 条目替换（registry InstanceRegistry 同构，原子粒度 = service
 * 实例集，ADR-007）。
 *
 * <p>条目存在性 = 服务存在性（≥1 实例）：DELETE 后空集即删条目——registry 侧空 holder
 * 回收、bootstrap 快照不含空服务，变更流与覆盖两路径语义收敛，空条目不滞留。
 *
 * <p>写侧约定：per-cluster 单线程（同步器线程）串行调用 {@link #replaceAll}/{@link #apply}，
 * 保同 service 变更序；读路径无锁（CHM 弱一致迭代 + 不可变值持引用过滤，零克隆）。
 */
public final class ProjectionRegistry {

    private final ConcurrentHashMap<String, ClusterProjection> clusters = new ConcurrentHashMap<>();

    /** bootstrap 全量覆盖：put 全部 + 删除快照外 key（中间态与逐条变更同级，最终一致）。 */
    public void replaceAll(String cluster, List<ServiceInstances> services) {
        projection(cluster).replaceAll(services);
    }

    /** 变更批次应用（批内同 service 逐条按产生序，后到覆盖先到）。 */
    public void apply(String cluster, List<ServiceChange> batch) {
        projection(cluster).apply(batch);
    }

    /** 选择器查询（D-D7）：显式 services 命中即返回条目（labels 过滤后可为空实例集）；
     * 空条目仅产生于 labels 过滤；全量查询不返回过滤后空条目。 */
    public List<ClusterServiceInstances> query(DiscoverySelector selector) {
        List<ClusterServiceInstances> result = new ArrayList<>();
        for (Map.Entry<String, ClusterProjection> entry : clusters.entrySet()) {
            entry.getValue().query(entry.getKey(), selector, result);
        }
        return result;
    }

    public Set<String> clusterIds() {
        return Set.copyOf(clusters.keySet());
    }

    /** 单条目读（订阅扇出旧值 lookup，D-S4）：无条目返回 null，无锁持引用。 */
    public ServiceInstances service(String cluster, ServiceKey key) {
        ClusterProjection projection = clusters.get(cluster);
        return projection == null ? null : projection.table.get(key);
    }

    /** 全条目读（重连覆盖 diff 的旧投影 T0，D-S7）：无锁持引用快照。 */
    public List<ServiceInstances> services(String cluster) {
        ClusterProjection projection = clusters.get(cluster);
        return projection == null ? List.of() : List.copyOf(projection.table.values());
    }

    private ClusterProjection projection(String cluster) {
        return clusters.computeIfAbsent(cluster, ignored -> new ClusterProjection());
    }

    private static final class ClusterProjection {

        private final ConcurrentHashMap<ServiceKey, ServiceInstances> table = new ConcurrentHashMap<>();

        void replaceAll(List<ServiceInstances> services) {
            Set<ServiceKey> present = new HashSet<>();
            for (ServiceInstances service : services) {
                if (service.instances().isEmpty()) {
                    continue; // 防御：快照理论上不含空服务（registry 空 holder 回收）
                }
                table.put(service.key(), service);
                present.add(service.key());
            }
            table.keySet().retainAll(present);
        }

        void apply(List<ServiceChange> batch) {
            for (ServiceChange service : batch) {
                for (InstanceChange change : service.changes()) {
                    apply(service.key(), change);
                }
            }
        }

        private void apply(ServiceKey key, InstanceChange change) {
            ServiceInstances current = table.get(key);
            switch (change.type()) {
                case NEW, UPDATE -> table.put(key, upsert(current, key, change.instance()));
                case DELETE -> {
                    if (current != null) {
                        ServiceInstances remaining =
                                without(current, change.instance().instanceId());
                        if (remaining == null) {
                            // instanceId 不存在（陈旧/重复 DELETE）：no-op，不伤同服务其余实例
                            // （语义对齐 registry InstanceRegistry——remove 未命中不动 holder）
                        } else if (remaining.instances().isEmpty()) {
                            table.remove(key); // 空即删（D-D2）
                        } else {
                            table.put(key, remaining);
                        }
                    } // 未知服务 DELETE 忽略
                }
                case UNKNOWN -> {
                    // 未知类型不可执行，丢弃（对端版本未知演进；丢失窗口由 re-bootstrap 自愈）
                }
            }
        }

        private static ServiceInstances upsert(ServiceInstances current, ServiceKey key, Instance instance) {
            if (current == null) {
                return new ServiceInstances(key, List.of(instance));
            }
            List<Instance> instances = new ArrayList<>(current.instances().size() + 1);
            boolean replaced = false;
            for (Instance existing : current.instances()) {
                if (existing.instanceId().equals(instance.instanceId())) {
                    instances.add(instance);
                    replaced = true;
                } else {
                    instances.add(existing);
                }
            }
            if (!replaced) {
                instances.add(instance);
            }
            return new ServiceInstances(key, instances);
        }

        /** 移除实例后的实例集；instanceId 不存在返回 null（无需重建）。 */
        private static ServiceInstances without(ServiceInstances current, String instanceId) {
            Instance found = null;
            for (Instance existing : current.instances()) {
                if (existing.instanceId().equals(instanceId)) {
                    found = existing;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            List<Instance> instances = new ArrayList<>(current.instances().size() - 1);
            for (Instance existing : current.instances()) {
                if (existing != found) {
                    instances.add(existing);
                }
            }
            return new ServiceInstances(current.key(), instances);
        }

        void query(String cluster, DiscoverySelector selector, List<ClusterServiceInstances> result) {
            if (!selector.services().isEmpty()) {
                // 显式集合（已去重）：命中条目即返回（labels 过滤后可为空实例集）；条目不存在不返回
                for (ServiceKey key : selector.services()) {
                    ServiceInstances service = table.get(key);
                    if (service != null) {
                        result.add(new ClusterServiceInstances(cluster, filter(service, selector.labels())));
                    }
                }
                return;
            }
            // 全量查询：过滤后为空不返回（防响应膨胀）
            for (ServiceInstances service : table.values()) {
                ServiceInstances filtered = filter(service, selector.labels());
                if (!filtered.instances().isEmpty()) {
                    result.add(new ClusterServiceInstances(cluster, filtered));
                }
            }
        }

        private static ServiceInstances filter(ServiceInstances service, Map<String, String> labels) {
            if (labels.isEmpty()) {
                return service;
            }
            List<Instance> matched = service.instances().stream()
                    .filter(instance -> instance.metadata().entrySet().containsAll(labels.entrySet()))
                    .toList();
            return new ServiceInstances(service.key(), matched);
        }
    }
}
