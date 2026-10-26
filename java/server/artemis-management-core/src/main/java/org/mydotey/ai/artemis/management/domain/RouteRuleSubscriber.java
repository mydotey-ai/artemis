package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 路由规则订阅者端口（规则流服务端回调，phase-2，模式同 {@link EjectionRuleSubscriber}）：
 * 单订阅者内串行调用；快照与增量携带规则集状态版本（dispatcher tick）。
 */
public interface RouteRuleSubscriber {

    /** 订阅建立即全量快照（ACTIVE 规则集，替换语义，含空集）+ 该规则集状态版本。 */
    void onSnapshot(List<RouteRule> rules, long version);

    /** 增量：upsert（覆盖语义，承载 CRUD / release / rollback 后的规则视图）。 */
    void onUpserted(RouteRule rule, long version);

    /** 增量：删除（规则删除或 INACTIVE 化——离开 ACTIVE 集）。 */
    void onDeleted(ServiceKey service, String name, long version);

    /** 断流（服务端注销；此后不再回调）。 */
    void close();
}
