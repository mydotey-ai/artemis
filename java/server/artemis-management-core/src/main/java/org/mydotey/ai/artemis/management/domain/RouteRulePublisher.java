package org.mydotey.ai.artemis.management.domain;

/**
 * 路由规则变更出口端口（应用层在 CRUD / release / rollback 落库后调用）：
 * 规则变更统一走 {@link #onUpserted}（流内容 = ACTIVE 集的判定收敛在实现——INACTIVE
 * 由分发器转推 deleted）；删除（规则移除）走 {@link #onDeleted}。版本（）供生效水位计算。
 */
public interface RouteRulePublisher {

    /** 规则变更（增/改/发布/回滚，任意状态）：ACTIVE 推 upserted、INACTIVE 转推 deleted。 */
    void onUpserted(RouteRule rule);

    /** 规则删除或 INACTIVE 化：推 deleted 增量（消费方幂等）。 */
    void onDeleted(ServiceKey service, String name);

    /** 当前规则集状态版本（水位语义，随每次变更单调 +1）。 */
    long version();
}
