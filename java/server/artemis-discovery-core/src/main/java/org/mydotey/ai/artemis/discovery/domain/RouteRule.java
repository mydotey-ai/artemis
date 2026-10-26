package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;
import java.util.Map;

/**
 * 路由规则视图（management 规则流下发，phase-2 只缓存不执行——selector 匹配与权重
 * 切分归 phase-3 过滤器链）。流内容 = ACTIVE 集（management 侧已过滤，无 status 字段）。
 */
public record RouteRule(ServiceKey service, String name, List<RouteRuleTarget> targets) {

    public RouteRule {
        targets = List.copyOf(targets);
    }

    /** 路由目标：selector 等值匹配（多键 AND）命中实例集分得权重；空 map = 兜底目标。 */
    public record RouteRuleTarget(Map<String, String> selector, int weight, int unreleasedWeight) {}
}
