package org.mydotey.ai.artemis.client.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/**
 * 单订阅本地视图（per-subscription）：不可变 ServiceView + CHM 发布，读路径无锁零深克隆
 * （guidelines §4.1；legacy 局限 #13 修复）。**写操作只在 client 的单线程 applier 上调用**
 * （apply/replaceAll 无并发）；对账读（checksums）与 getInstances 为并发读（弱一致）。
 */
final class LocalDiscoveryCache {

    /** cluster 归属的不可变视图（构建后不再修改）；union 供零拷贝读。 */
    static final class ServiceView {

        final Map<String, List<ServiceInstance>> byCluster;
        final List<ServiceInstance> union;

        ServiceView(Map<String, List<ServiceInstance>> byCluster, List<ServiceInstance> union) {
            Map<String, List<ServiceInstance>> copied = new LinkedHashMap<>();
            for (Map.Entry<String, List<ServiceInstance>> entry : byCluster.entrySet()) {
                copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            this.byCluster = Collections.unmodifiableMap(copied);
            this.union = Collections.unmodifiableList(new ArrayList<>(union));
        }
    }

    /** 全量替换的原子单元（cluster 维度条目）。 */
    static final class ClusterEntry {

        final String cluster;
        final ServiceId service;
        final List<ServiceInstance> instances;

        ClusterEntry(String cluster, ServiceId service, List<ServiceInstance> instances) {
            this.cluster = cluster;
            this.service = service;
            this.instances = instances;
        }
    }

    private final ConcurrentHashMap<ServiceId, ServiceView> views = new ConcurrentHashMap<>();

    /** 增量应用（单 service 变更批，applier 线程）：NEW/UPDATE upsert、DELETE 移除、UNKNOWN 忽略；返回视图是否实际变化。 */
    boolean applyChange(String cluster, ServiceChange change) {
        ServiceId key = change.key;
        boolean changed = false;
        for (InstanceChange item : change.changes) {
            if (item.type == InstanceChange.Type.UNKNOWN) {
                continue;
            }
            boolean applied = item.type == InstanceChange.Type.DELETE
                    ? applyDelete(cluster, key, item.instanceId)
                    : applyUpsert(cluster, key, item.instance);
            changed |= applied; // 同批逐条全部应用（保持产生序），任一变化即通知
        }
        return changed;
    }

    private boolean applyUpsert(String cluster, ServiceId key, ServiceInstance instance) {
        ServiceView current = views.get(key);
        Map<String, List<ServiceInstance>> byCluster = current == null
                ? new LinkedHashMap<String, List<ServiceInstance>>()
                : new LinkedHashMap<>(current.byCluster);
        List<ServiceInstance> clusterList = new ArrayList<>(
                byCluster.containsKey(cluster) ? byCluster.get(cluster) : Collections.<ServiceInstance>emptyList());
        if (!replaceById(clusterList, instance)) {
            return false; // 幂等重放：同 id 同内容，视图不变
        }
        byCluster.put(cluster, clusterList);
        views.put(key, new ServiceView(byCluster, unionOf(byCluster)));
        return true;
    }

    private boolean applyDelete(String cluster, ServiceId key, String instanceId) {
        ServiceView current = views.get(key);
        if (current == null) {
            return false;
        }
        List<ServiceInstance> clusterList = current.byCluster.get(cluster);
        if (clusterList == null || !containsId(clusterList, instanceId)) {
            return false;
        }
        List<ServiceInstance> remaining = new ArrayList<>(clusterList);
        removeById(remaining, instanceId);
        Map<String, List<ServiceInstance>> byCluster = new LinkedHashMap<>(current.byCluster);
        if (remaining.isEmpty()) {
            byCluster.remove(cluster); // 空即删（条目存在性 = 有实例）
        } else {
            byCluster.put(cluster, remaining);
        }
        if (byCluster.isEmpty()) {
            views.remove(key);
        } else {
            views.put(key, new ServiceView(byCluster, unionOf(byCluster)));
        }
        return true;
    }

    /** 全量替换（bootstrap/对账/15min 刷新，applier 线程）：本地有而响应无 = 服务消失；返回实际变化的 service 集合。 */
    List<ServiceId> replaceAll(List<ClusterEntry> entries) {
        Map<ServiceId, Map<String, List<ServiceInstance>>> grouped = new LinkedHashMap<>();
        for (ClusterEntry entry : entries) {
            if (entry.instances == null || entry.instances.isEmpty()) {
                continue; // 缺项 = 权威空（D-F1）
            }
            Map<String, List<ServiceInstance>> byCluster = grouped.get(entry.service);
            if (byCluster == null) {
                byCluster = new LinkedHashMap<>();
                grouped.put(entry.service, byCluster);
            }
            byCluster.put(entry.cluster, new ArrayList<>(entry.instances));
        }
        List<ServiceId> changed = new ArrayList<>();
        for (Map.Entry<ServiceId, Map<String, List<ServiceInstance>>> entry : grouped.entrySet()) {
            ServiceView view = new ServiceView(entry.getValue(), unionOf(entry.getValue()));
            ServiceView old = views.get(entry.getKey());
            // union 不变但 cluster 归属变化（多集群 rebalance）也必须写回，否则 per-cluster checksum 永久不一致
            if (old == null || !old.union.equals(view.union) || !old.byCluster.equals(view.byCluster)) {
                views.put(entry.getKey(), view);
                changed.add(entry.getKey());
            }
        }
        for (ServiceId key : new ArrayList<>(views.keySet())) {
            if (!grouped.containsKey(key)) {
                views.remove(key);
                changed.add(key);
            }
        }
        return changed;
    }

    /** 本地视图（union，零拷贝引用）；无数据返回空列表。并发读安全。 */
    List<ServiceInstance> getInstances(ServiceId service) {
        ServiceView view = views.get(service);
        return view == null ? Collections.<ServiceInstance>emptyList() : view.union;
    }

    /** 当前视图的落盘条目序列（弱一致快照）。 */
    List<ClusterEntry> entries() {
        List<ClusterEntry> result = new ArrayList<>();
        for (Map.Entry<ServiceId, ServiceView> view : views.entrySet()) {
            for (Map.Entry<String, List<ServiceInstance>> entry :
                    view.getValue().byCluster.entrySet()) {
                result.add(new ClusterEntry(entry.getKey(), view.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 本地 per-cluster checksum 表（对账用，弱一致读）：cluster → (service → checksum)。
     * labels 视图的 checksum 口径与服务端一致（服务端已翻译视图语义）。
     */
    Map<String, Map<ServiceId, Integer>> checksums() {
        Map<String, Map<ServiceId, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<ServiceId, ServiceView> view : views.entrySet()) {
            for (Map.Entry<String, List<ServiceInstance>> entry :
                    view.getValue().byCluster.entrySet()) {
                Map<ServiceId, Integer> perCluster = result.get(entry.getKey());
                if (perCluster == null) {
                    perCluster = new LinkedHashMap<>();
                    result.put(entry.getKey(), perCluster);
                }
                perCluster.put(view.getKey(), DiscoveryChecksum.of(entry.getValue()));
            }
        }
        return result;
    }

    int serviceCount() {
        return views.size();
    }

    Set<ServiceId> services() {
        return views.keySet();
    }

    private static List<ServiceInstance> unionOf(Map<String, List<ServiceInstance>> byCluster) {
        List<ServiceInstance> union = new ArrayList<>();
        for (List<ServiceInstance> list : byCluster.values()) {
            union.addAll(list);
        }
        return union;
    }

    /** 同 id 替换（存在同内容 = 无变化返回 false）；不存在追加。 */
    private static boolean replaceById(List<ServiceInstance> list, ServiceInstance instance) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getInstanceId().equals(instance.getInstanceId())) {
                if (list.get(i).equals(instance)) {
                    return false;
                }
                list.set(i, instance);
                return true;
            }
        }
        list.add(instance);
        return true;
    }

    private static boolean containsId(List<ServiceInstance> list, String instanceId) {
        for (ServiceInstance instance : list) {
            if (instance.getInstanceId().equals(instanceId)) {
                return true;
            }
        }
        return false;
    }

    private static void removeById(List<ServiceInstance> list, String instanceId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getInstanceId().equals(instanceId)) {
                list.remove(i);
                return;
            }
        }
    }
}
