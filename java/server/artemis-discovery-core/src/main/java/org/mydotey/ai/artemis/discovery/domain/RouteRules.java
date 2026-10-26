package org.mydotey.ai.artemis.discovery.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 不可变路由规则集（volatile 发布，读路径无锁）：规则变更 = 整集重建（快照全量 /
 * 增量 upsert/delete——集合操作幂等，等号/旧号事件安全重放）。按 service 分组索引
 * （Group filter 判定与扇出枚举 O(1) 定位；流内容 = ACTIVE 集，management 侧已过滤）。
 * ponytail: 管理操作级频率整集重建可忽略，规则规模到 10 万级再改增量结构。
 */
public final class RouteRules {

    public static final RouteRules EMPTY = new RouteRules(Map.of());

    private final Map<ServiceKey, Map<String, RouteRule>> byService;

    private RouteRules(Map<ServiceKey, Map<String, RouteRule>> byService) {
        this.byService = byService;
    }

    /** 全量快照构建（替换语义）。 */
    public static RouteRules of(Iterable<RouteRule> rules) {
        Map<ServiceKey, Map<String, RouteRule>> byService = new HashMap<>();
        for (RouteRule rule : rules) {
            byService
                    .computeIfAbsent(rule.service(), ignored -> new HashMap<>())
                    .put(rule.name(), rule);
        }
        return copyOf(byService);
    }

    /** 增量 upsert（幂等：键等值同内容返回自身，覆盖语义）。 */
    public RouteRules with(RouteRule rule) {
        Map<String, RouteRule> existing = byService.get(rule.service());
        if (existing != null && Objects.equals(existing.get(rule.name()), rule)) {
            return this;
        }
        Map<ServiceKey, Map<String, RouteRule>> next = new HashMap<>(byService);
        next.compute(rule.service(), (service, rules) -> {
            Map<String, RouteRule> merged = rules == null ? new HashMap<>() : new HashMap<>(rules);
            merged.put(rule.name(), rule);
            return merged;
        });
        return copyOf(next);
    }

    /** 增量删除（幂等：不存在返回自身）。 */
    public RouteRules without(RouteRuleKey key) {
        Map<String, RouteRule> existing = byService.get(key.service());
        if (existing == null || !existing.containsKey(key.name())) {
            return this;
        }
        Map<ServiceKey, Map<String, RouteRule>> next = new HashMap<>(byService);
        next.compute(key.service(), (service, rules) -> {
            Map<String, RouteRule> remaining = new HashMap<>(rules);
            remaining.remove(key.name());
            return remaining.isEmpty() ? null : remaining;
        });
        return copyOf(next);
    }

    public boolean isEmpty() {
        return byService.isEmpty();
    }

    public int size() {
        return byService.values().stream().mapToInt(Map::size).sum();
    }

    /** 全量视图（升序稳定序，排障/IT 断言用）。 */
    public List<RouteRule> list() {
        return byService.values().stream()
                .flatMap(rules -> rules.values().stream())
                .sorted(java.util.Comparator.comparing(
                                (RouteRule rule) -> rule.service().namespace())
                        .thenComparing(rule -> rule.service().service())
                        .thenComparing(RouteRule::name))
                .toList();
    }

    public Optional<RouteRule> find(ServiceKey service, String name) {
        Map<String, RouteRule> rules = byService.get(service);
        return Optional.ofNullable(rules == null ? null : rules.get(name));
    }

    /** 服务内全部规则（无规则返回空 List）。 */
    public List<RouteRule> rules(ServiceKey service) {
        Map<String, RouteRule> rules = byService.get(service);
        return rules == null ? List.of() : List.copyOf(rules.values());
    }

    /** 规则覆盖的服务键集（扇出 diff 枚举）。 */
    public Set<ServiceKey> services() {
        return byService.keySet();
    }

    /**
     * Group filter 判定（phase-3 设计 §1）：服务无规则全可见（无规则 = 无治理意图）；
     * 多规则 AND（每条独立约束）；单规则内可见 ⟺ 命中目标中任一生效权重 > 0——
     * 命中目标 = 非空 selector（多键 AND 等值匹配实例 metadata）命中的目标，未命中
     * 任何 selector 的实例归兜底目标（空 selector，至多一条）。权重执行面 = 0/非 0
     * 二值承接（weight 0 摘出视图，比例归调用方侧消费）。
     */
    public boolean isVisible(ServiceKey service, Map<String, String> metadata) {
        Map<String, RouteRule> rules = byService.get(service);
        if (rules == null) {
            return true;
        }
        for (RouteRule rule : rules.values()) {
            if (!visibleUnder(rule, metadata)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 命中 0 权重目标的实例不归兜底（兜底只承接未匹配任何 selector 约定的流量）。
     * 纵深防御（review #1/#2）：空 targets 规则（management 校验 1–10 条，但 wire
     * 不设防）视为无治理意图全可见——规则数据损坏不得静默摘除整个服务；多兜底目标
     * （违反「至多一条」等值约束）取首个生效（targets 保序，确定性降级）。
     */
    private static boolean visibleUnder(RouteRule rule, Map<String, String> metadata) {
        if (rule.targets().isEmpty()) {
            return true;
        }
        boolean matched = false;
        RouteRule.RouteRuleTarget fallback = null;
        for (RouteRule.RouteRuleTarget target : rule.targets()) {
            if (target.selector().isEmpty()) {
                if (fallback == null) {
                    fallback = target;
                }
            } else if (metadata.entrySet().containsAll(target.selector().entrySet())) {
                matched = true;
                if (target.weight() > 0) {
                    return true;
                }
            }
        }
        return !matched && fallback != null && fallback.weight() > 0;
    }

    private static RouteRules copyOf(Map<ServiceKey, Map<String, RouteRule>> source) {
        if (source.isEmpty()) {
            return EMPTY;
        }
        Map<ServiceKey, Map<String, RouteRule>> copied = new HashMap<>();
        for (Map.Entry<ServiceKey, Map<String, RouteRule>> entry : source.entrySet()) {
            copied.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new RouteRules(Map.copyOf(copied));
    }
}
