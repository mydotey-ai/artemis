package org.mydotey.ai.artemis.client.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/**
 * 本地实例集（SDK 唯一事实源）：不可变快照 + volatile 发布，读路径无锁；
 * 写侧低频（实例操作），synchronized copy-on-write 整体替换。
 */
final class LocalInstanceStore {

    private volatile Map<ServiceId, Map<String, ServiceInstance>> instances = Collections.emptyMap();

    /** upsert（register 无条件覆盖 / update 幂等 upsert 在 SDK 侧同构）。 */
    synchronized void put(ServiceInstance instance) {
        Map<ServiceId, Map<String, ServiceInstance>> next = copy();
        Map<String, ServiceInstance> group = next.get(instance.getService());
        Map<String, ServiceInstance> newGroup = new TreeMap<String, ServiceInstance>();
        if (group != null) {
            newGroup.putAll(group);
        }
        newGroup.put(instance.getInstanceId(), instance);
        next.put(instance.getService(), Collections.unmodifiableMap(newGroup));
        instances = next;
    }

    /** 当前实例（重试前重读：注销则 null、更新则返回最新值）。 */
    ServiceInstance get(ServiceId service, String instanceId) {
        Map<String, ServiceInstance> group = instances.get(service);
        return group == null ? null : group.get(instanceId);
    }

    /** 移除实例；组空则清理组（心跳不携带空组）。 */
    synchronized void remove(ServiceId service, String instanceId) {
        Map<ServiceId, Map<String, ServiceInstance>> current = instances;
        Map<String, ServiceInstance> group = current.get(service);
        if (group == null || !group.containsKey(instanceId)) {
            return;
        }
        Map<ServiceId, Map<String, ServiceInstance>> next = copy();
        Map<String, ServiceInstance> newGroup = new TreeMap<String, ServiceInstance>(group);
        newGroup.remove(instanceId);
        if (newGroup.isEmpty()) {
            next.remove(service);
        } else {
            next.put(service, Collections.unmodifiableMap(newGroup));
        }
        instances = next;
    }

    /** 当前快照（心跳全量上报 / close 全量注销的数据源）。 */
    Map<ServiceId, List<ServiceInstance>> snapshot() {
        Map<ServiceId, List<ServiceInstance>> snapshot = new LinkedHashMap<ServiceId, List<ServiceInstance>>();
        for (Map.Entry<ServiceId, Map<String, ServiceInstance>> entry : instances.entrySet()) {
            snapshot.put(
                    entry.getKey(),
                    Collections.unmodifiableList(new java.util.ArrayList<ServiceInstance>(
                            entry.getValue().values())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    boolean isEmpty() {
        return instances.isEmpty();
    }

    /** 本地实例总数（gauge 数据源）。 */
    int instanceCount() {
        int count = 0;
        for (Map<String, ServiceInstance> group : instances.values()) {
            count += group.size();
        }
        return count;
    }

    java.util.Collection<InstanceId> instanceIds() {
        java.util.List<InstanceId> ids = new java.util.ArrayList<InstanceId>();
        for (Map.Entry<ServiceId, Map<String, ServiceInstance>> entry : instances.entrySet()) {
            for (String instanceId : entry.getValue().keySet()) {
                ids.add(InstanceId.newBuilder()
                        .service(entry.getKey())
                        .instanceId(instanceId)
                        .build());
            }
        }
        return ids;
    }

    private Map<ServiceId, Map<String, ServiceInstance>> copy() {
        return new LinkedHashMap<ServiceId, Map<String, ServiceInstance>>(instances);
    }
}
