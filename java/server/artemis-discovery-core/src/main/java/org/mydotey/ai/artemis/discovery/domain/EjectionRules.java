package org.mydotey.ai.artemis.discovery.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 不可变摘除规则集（volatile 发布，读路径无锁 O(1) 判定），按 service 分组存储——
 * 规则变更扇出的对称差计算按 service 枚举（D-E6）。规则变更 = 整集重建（快照全量 /
 * 增量 with/without）：管理操作级频率，重建代价可忽略
 * （ponytail: 规则规模到 10 万级再改增量结构）。
 */
public final class EjectionRules {

    public static final EjectionRules EMPTY = new EjectionRules(Map.of());

    private final Map<ServiceKey, Set<String>> ejected;

    private EjectionRules(Map<ServiceKey, Set<String>> ejected) {
        this.ejected = ejected;
    }

    /** 全量快照构建（替换语义）。 */
    public static EjectionRules of(Iterable<EjectionRule> rules) {
        Map<ServiceKey, Set<String>> byService = new HashMap<>();
        for (EjectionRule rule : rules) {
            byService
                    .computeIfAbsent(rule.service(), ignored -> new HashSet<>())
                    .add(rule.instanceId());
        }
        Map<ServiceKey, Set<String>> copied = new HashMap<>();
        for (Map.Entry<ServiceKey, Set<String>> entry : byService.entrySet()) {
            copied.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return new EjectionRules(Map.copyOf(copied));
    }

    public boolean isEmpty() {
        return ejected.isEmpty();
    }

    public boolean isEjected(ServiceKey service, String instanceId) {
        Set<String> instances = ejected.get(service);
        return instances != null && instances.contains(instanceId);
    }

    /** 增量摘除（幂等：已存在返回自身）。 */
    public EjectionRules with(EjectionRule rule) {
        if (isEjected(rule.service(), rule.instanceId())) {
            return this;
        }
        Map<ServiceKey, Set<String>> next = new HashMap<>(ejected);
        next.compute(rule.service(), (key, instances) -> {
            Set<String> merged = instances == null ? new HashSet<>() : new HashSet<>(instances);
            merged.add(rule.instanceId());
            return Set.copyOf(merged);
        });
        return new EjectionRules(Map.copyOf(next));
    }

    /** 增量恢复（幂等：未摘除返回自身）。 */
    public EjectionRules without(ServiceKey service, String instanceId) {
        if (!isEjected(service, instanceId)) {
            return this;
        }
        Map<ServiceKey, Set<String>> next = new HashMap<>(ejected);
        next.compute(service, (key, instances) -> {
            Set<String> remaining = new HashSet<>(instances);
            remaining.remove(instanceId);
            return remaining.isEmpty() ? null : Set.copyOf(remaining);
        });
        return new EjectionRules(Map.copyOf(next));
    }

    /** 有摘除规则的服务集（扇出 diff 枚举）。 */
    public Set<ServiceKey> services() {
        return ejected.keySet();
    }

    /** 指定服务的摘除实例 id 集（扇出 diff；无规则返回空）。 */
    public Set<String> instances(ServiceKey service) {
        return ejected.getOrDefault(service, Set.of());
    }
}
