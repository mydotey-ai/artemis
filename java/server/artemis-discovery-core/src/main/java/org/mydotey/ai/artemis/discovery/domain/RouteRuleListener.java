package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/**
 * 路由规则流监听端口（gRPC 回调线程调用，实现方须非阻塞：入队移交，不做应用逻辑；
 * 模式同 {@link EjectionRuleListener}）。事件语义：快照 = 替换（含空集）；
 * 增量 upserted 为覆盖语义；流内按产生序。
 */
public interface RouteRuleListener {

    /** 首条全量快照（ACTIVE 规则集，含空集，替换语义）+ 该规则集状态版本。 */
    void onSnapshot(List<RouteRule> rules, long version);

    /** 增量：upsert（覆盖，承载 CRUD / release / rollback 后的规则视图）。 */
    void onUpserted(RouteRule rule, long version);

    /** 增量：删除（规则删除或 INACTIVE 化）。 */
    void onDeleted(RouteRuleKey key, long version);

    /** 流终止（对端断流/错误/完成）；此后该订阅不再回调。 */
    void onClosed();
}
