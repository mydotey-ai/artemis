package org.mydotey.ai.artemis.discovery.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投影覆盖 diff（D-S7）：旧投影 T0 → 新快照 T1 的变更批生成——服务消失全 DELETE、
 * 新建全 NEW、共存服务按实例 equals diff 增删改。纯函数，重连覆盖路径（同步线程）
 * 与稳态增量共用扇出语义。
 *
 * <p>入参为旧投影 by-key 索引（调用方与扇出旧值 lookup 共用同一张图，避免重连路径重复构建）；
 * 本方法不修改入参 map。
 */
public final class ProjectionDiff {

    private ProjectionDiff() {}

    public static List<ServiceChange> diff(Map<ServiceKey, ServiceInstances> oldByKey, List<ServiceInstances> fresh) {
        Set<ServiceKey> seen = new HashSet<>();
        List<ServiceChange> out = new ArrayList<>();
        for (ServiceInstances service : fresh) {
            seen.add(service.key());
            ServiceInstances previous = oldByKey.get(service.key());
            if (previous == null) {
                out.add(all(service, ChangeType.NEW));
                continue;
            }
            List<InstanceChange> changes = diffService(previous, service);
            if (!changes.isEmpty()) {
                out.add(new ServiceChange(service.key(), changes));
            }
        }
        for (ServiceInstances vanished : oldByKey.values()) {
            if (!seen.contains(vanished.key())) {
                out.add(all(vanished, ChangeType.DELETE)); // 覆盖后消失的服务：清空信号
            }
        }
        return out;
    }

    /** 共存服务实例集 diff：NEW/UPDATE 按新序、DELETE 按旧序（不同实例间顺序无语义，取确定序）。 */
    private static List<InstanceChange> diffService(ServiceInstances old, ServiceInstances fresh) {
        Map<String, Instance> oldById = new HashMap<>();
        for (Instance instance : old.instances()) {
            oldById.put(instance.instanceId(), instance);
        }
        List<InstanceChange> out = new ArrayList<>();
        for (Instance instance : fresh.instances()) {
            Instance previous = oldById.remove(instance.instanceId());
            if (previous == null) {
                out.add(new InstanceChange(instance, ChangeType.NEW));
            } else if (!previous.equals(instance)) {
                out.add(new InstanceChange(instance, ChangeType.UPDATE));
            }
        }
        for (Instance vanished : oldById.values()) {
            out.add(new InstanceChange(vanished, ChangeType.DELETE)); // 携带旧实例完整字段
        }
        return out;
    }

    private static ServiceChange all(ServiceInstances service, ChangeType type) {
        List<InstanceChange> changes = new ArrayList<>(service.instances().size());
        for (Instance instance : service.instances()) {
            changes.add(new InstanceChange(instance, type));
        }
        return new ServiceChange(service.key(), changes);
    }
}
