package org.mydotey.ai.artemis.discovery.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 不可变路由规则集（volatile 发布，读路径无锁）：规则变更 = 整集重建（快照全量 /
 * 增量 upsert/delete——集合操作幂等，等号/旧号事件安全重放）。phase-2 为规则缓存
 * （本地视图 + 排障出口）；phase-3 Group filter 接入执行时按 service 索引取用
 * （ponytail: 管理操作级频率整集重建可忽略，规则规模到 10 万级再改增量结构）。
 */
public final class RouteRules {

    public static final RouteRules EMPTY = new RouteRules(Map.of());

    private final Map<RouteRuleKey, RouteRule> rules;

    private RouteRules(Map<RouteRuleKey, RouteRule> rules) {
        this.rules = rules;
    }

    /** 全量快照构建（替换语义）。 */
    public static RouteRules of(Iterable<RouteRule> rules) {
        Map<RouteRuleKey, RouteRule> byKey = new HashMap<>();
        for (RouteRule rule : rules) {
            byKey.put(new RouteRuleKey(rule.service(), rule.name()), rule);
        }
        return byKey.isEmpty() ? EMPTY : new RouteRules(Map.copyOf(byKey));
    }

    /** 增量 upsert（幂等：键等值同内容返回自身，覆盖语义）。 */
    public RouteRules with(RouteRule rule) {
        RouteRuleKey key = new RouteRuleKey(rule.service(), rule.name());
        if (java.util.Objects.equals(rules.get(key), rule)) {
            return this;
        }
        Map<RouteRuleKey, RouteRule> next = new HashMap<>(rules);
        next.put(key, rule);
        return new RouteRules(Map.copyOf(next));
    }

    /** 增量删除（幂等：不存在返回自身）。 */
    public RouteRules without(RouteRuleKey key) {
        if (!rules.containsKey(key)) {
            return this;
        }
        Map<RouteRuleKey, RouteRule> next = new HashMap<>(rules);
        next.remove(key);
        return next.isEmpty() ? EMPTY : new RouteRules(Map.copyOf(next));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** 全量视图（升序稳定序，排障/IT 断言用）。 */
    public List<RouteRule> list() {
        return rules.values().stream()
                .sorted(java.util.Comparator.comparing(
                                (RouteRule rule) -> rule.service().namespace())
                        .thenComparing(rule -> rule.service().service())
                        .thenComparing(RouteRule::name))
                .toList();
    }

    public Optional<RouteRule> find(ServiceKey service, String name) {
        return Optional.ofNullable(rules.get(new RouteRuleKey(service, name)));
    }
}
