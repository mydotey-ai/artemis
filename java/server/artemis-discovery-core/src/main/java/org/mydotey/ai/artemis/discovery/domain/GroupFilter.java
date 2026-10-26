package org.mydotey.ai.artemis.discovery.domain;

/**
 * 分组路由过滤器（phase-3，架构 §4.2/§5.2、ADR-013）：route-rule 生效集本地缓存
 * （规则流同步线程唯一写入口），断流不清缓存（D-E3）——management 不可用时最后已知
 * 规则继续。判定语义（selector 等值匹配 + 生效权重二值承接）见
 * {@link RouteRules#isVisible}，与规则扇出同源（视图直投与过滤器判定单一实现）。
 */
public final class GroupFilter implements DiscoveryFilter {

    private volatile RouteRules rules = RouteRules.EMPTY;

    public void replace(RouteRules rules) {
        this.rules = rules;
    }

    public RouteRules rules() {
        return rules;
    }

    @Override
    public boolean isVisible(ServiceKey service, Instance instance) {
        return rules.isVisible(service, instance.metadata());
    }
}
