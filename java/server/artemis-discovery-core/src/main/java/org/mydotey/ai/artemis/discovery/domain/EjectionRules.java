package org.mydotey.ai.artemis.discovery.domain;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.mydotey.ai.artemis.common.governance.EjectionScope;

/**
 * 不可变摘除规则集（volatile 发布，读路径无锁 O(1) 判定）：instance 级按 service 分组
 * （扇出按 service 枚举，D-E6），selector 级按 scope 各自值集（按实例 metadata 匹配，
 * 全局生效——新注册实例天然命中）。规则变更 = 整集重建（快照全量 / 增量 with/without）：
 * 管理操作级频率，重建代价可忽略（ponytail: 规则规模到 10 万级再改增量结构）。
 */
public final class EjectionRules {

    public static final EjectionRules EMPTY = new EjectionRules(Map.of(), Map.of());

    private final Map<ServiceKey, Set<String>> ejectedInstances;
    private final Map<EjectionScope, Set<String>> ejectedSelectors;

    private EjectionRules(
            Map<ServiceKey, Set<String>> ejectedInstances, Map<EjectionScope, Set<String>> ejectedSelectors) {
        this.ejectedInstances = ejectedInstances;
        this.ejectedSelectors = ejectedSelectors;
    }

    /** 全量快照构建（替换语义）。 */
    public static EjectionRules of(Iterable<EjectionRule> rules) {
        Map<ServiceKey, Set<String>> byService = new HashMap<>();
        Map<EjectionScope, Set<String>> byScope = new EnumMap<>(EjectionScope.class);
        for (EjectionRule rule : rules) {
            if (rule.scope() == EjectionScope.INSTANCE) {
                byService
                        .computeIfAbsent(rule.service(), ignored -> new HashSet<>())
                        .add(rule.instanceId());
            } else {
                byScope.computeIfAbsent(rule.scope(), ignored -> new HashSet<>())
                        .add(rule.value());
            }
        }
        return new EjectionRules(copyInstances(byService), copySelectors(byScope));
    }

    public boolean isEmpty() {
        return ejectedInstances.isEmpty() && ejectedSelectors.isEmpty();
    }

    /** 四级级联判定：instance 级 (service, instanceId) 等值；selector 级 metadata[scope.key] 等值。 */
    public boolean isEjected(ServiceKey service, String instanceId, Map<String, String> metadata) {
        Set<String> instances = ejectedInstances.get(service);
        if (instances != null && instances.contains(instanceId)) {
            return true;
        }
        for (Map.Entry<EjectionScope, Set<String>> entry : ejectedSelectors.entrySet()) {
            String value = metadata == null ? null : metadata.get(entry.getKey().metadataKey());
            if (value != null && entry.getValue().contains(value)) {
                return true;
            }
        }
        return false;
    }

    /** 增量摘除（幂等：已存在返回自身）。 */
    public EjectionRules with(EjectionRule rule) {
        if (rule.scope() == EjectionScope.INSTANCE) {
            Set<String> existing = ejectedInstances.get(rule.service());
            if (existing != null && existing.contains(rule.instanceId())) {
                return this;
            }
            Map<ServiceKey, Set<String>> next = new HashMap<>(ejectedInstances);
            next.compute(rule.service(), (key, instances) -> {
                Set<String> merged = instances == null ? new HashSet<>() : new HashSet<>(instances);
                merged.add(rule.instanceId());
                return Set.copyOf(merged);
            });
            return new EjectionRules(copyInstances(next), ejectedSelectors);
        }
        if (ejectedSelectors.getOrDefault(rule.scope(), Set.of()).contains(rule.value())) {
            return this;
        }
        Map<EjectionScope, Set<String>> next = selectorsForMerge();
        next.compute(rule.scope(), (scope, values) -> {
            Set<String> merged = values == null ? new HashSet<>() : new HashSet<>(values);
            merged.add(rule.value());
            return Set.copyOf(merged);
        });
        return new EjectionRules(ejectedInstances, copySelectors(next));
    }

    /** 增量恢复（幂等：未摘除返回自身）。 */
    public EjectionRules without(EjectionTarget target) {
        if (target.scope() == EjectionScope.INSTANCE) {
            Set<String> existing = ejectedInstances.get(target.service());
            if (existing == null || !existing.contains(target.instanceId())) {
                return this;
            }
            Map<ServiceKey, Set<String>> next = new HashMap<>(ejectedInstances);
            next.compute(target.service(), (key, instances) -> {
                Set<String> remaining = new HashSet<>(instances);
                remaining.remove(target.instanceId());
                return remaining.isEmpty() ? null : Set.copyOf(remaining);
            });
            return new EjectionRules(copyInstances(next), ejectedSelectors);
        }
        Set<String> existing = ejectedSelectors.get(target.scope());
        if (existing == null || !existing.contains(target.value())) {
            return this;
        }
        Map<EjectionScope, Set<String>> next = selectorsForMerge();
        next.compute(target.scope(), (scope, values) -> {
            Set<String> remaining = new HashSet<>(values);
            remaining.remove(target.value());
            return remaining.isEmpty() ? null : Set.copyOf(remaining);
        });
        return new EjectionRules(ejectedInstances, copySelectors(next));
    }

    /** 有 instance 级规则的服务集（扇出 diff 枚举）。 */
    public Set<ServiceKey> services() {
        return ejectedInstances.keySet();
    }

    /** selector 级值集（判等用；快照语义）。 */
    public Map<EjectionScope, Set<String>> selectors() {
        return ejectedSelectors;
    }

    /** 指定服务的 instance 级摘除 id 集（扇出 diff；无规则返回空）。 */
    public Set<String> instances(ServiceKey service) {
        return ejectedInstances.getOrDefault(service, Set.of());
    }

    private static Map<ServiceKey, Set<String>> copyInstances(Map<ServiceKey, Set<String>> byService) {
        Map<ServiceKey, Set<String>> copied = new HashMap<>();
        for (Map.Entry<ServiceKey, Set<String>> entry : byService.entrySet()) {
            copied.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return copied.isEmpty() ? Map.of() : Map.copyOf(copied); // Map.copyOf 拒绝空 map
    }

    /** EnumMap 拷贝构造拒绝空 map：空集（Map.of()）从类型构造起。 */
    private Map<EjectionScope, Set<String>> selectorsForMerge() {
        return ejectedSelectors.isEmpty() ? new EnumMap<>(EjectionScope.class) : new EnumMap<>(ejectedSelectors);
    }

    private static Map<EjectionScope, Set<String>> copySelectors(Map<EjectionScope, Set<String>> byScope) {
        Map<EjectionScope, Set<String>> copied = new EnumMap<>(EjectionScope.class);
        for (Map.Entry<EjectionScope, Set<String>> entry : byScope.entrySet()) {
            copied.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return copied.isEmpty() ? Map.of() : Map.copyOf(copied);
    }
}
