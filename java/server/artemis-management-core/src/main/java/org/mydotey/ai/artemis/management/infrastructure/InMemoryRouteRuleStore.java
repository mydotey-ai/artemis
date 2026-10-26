package org.mydotey.ai.artemis.management.infrastructure;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleStore;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 路由规则内存实现（内存态，重启丢失；DB 态由 MyBatis 实现接管）。
 * 键 (ServiceKey, name)；insert/update/delete 走 ConcurrentHashMap 原子原语，
 * 无读-改-写复合段（设计 §6 零锁决策）。
 */
public final class InMemoryRouteRuleStore implements RouteRuleStore {

    private record Key(ServiceKey service, String name) {}

    private final ConcurrentHashMap<Key, RouteRule> rules = new ConcurrentHashMap<>();

    @Override
    public RouteRule find(ServiceKey service, String name) {
        return rules.get(new Key(service, name));
    }

    @Override
    public List<RouteRule> findAll() {
        return List.copyOf(rules.values());
    }

    @Override
    public boolean insert(RouteRule rule) {
        return rules.putIfAbsent(key(rule), rule) == null;
    }

    @Override
    public boolean update(RouteRule rule) {
        return rules.replace(key(rule), rule) != null;
    }

    @Override
    public boolean delete(ServiceKey service, String name) {
        return rules.remove(new Key(service, name)) != null;
    }

    private static Key key(RouteRule rule) {
        return new Key(rule.service(), rule.name());
    }
}
