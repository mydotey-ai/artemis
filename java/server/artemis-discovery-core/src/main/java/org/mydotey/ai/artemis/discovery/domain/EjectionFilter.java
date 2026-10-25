package org.mydotey.ai.artemis.discovery.domain;

/**
 * 摘除过滤器：规则本地缓存（management 规则流同步线程唯一写入口）；缓存只在收到
 * 快照时替换、断连不清空（D-E3）——management 不可用时最后已知规则继续。
 * 四级判定（instance 级 + selector 级 metadata 匹配）见 {@link EjectionRules#isEjected}。
 */
public final class EjectionFilter implements DiscoveryFilter {

    private volatile EjectionRules rules = EjectionRules.EMPTY;

    public void replace(EjectionRules rules) {
        this.rules = rules;
    }

    public EjectionRules rules() {
        return rules;
    }

    @Override
    public boolean isVisible(ServiceKey service, Instance instance) {
        return !rules.isEjected(service, instance.instanceId(), instance.metadata());
    }
}
