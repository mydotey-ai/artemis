package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/**
 * 摘除规则流监听端口（gRPC 回调线程调用，实现方须非阻塞：入队移交，不做应用逻辑）。
 * 事件语义（D-E7）：快照 = 替换（含空集）；增量 = 作用于最新集；流内按产生序。
 * phase-5：事件携带规则集状态版本（水位语义——应用后经 {@link EjectionRuleSource#ack} 上报）。
 */
public interface EjectionRuleListener {

    /** 首条全量快照（含空集，替换语义）+ 该规则集状态版本。 */
    void onSnapshot(List<EjectionRule> rules, long version);

    /** 增量：摘除（版本 = 触发翻转记录 sequence）。 */
    void onEjected(EjectionRule rule, long version);

    /** 增量：恢复（四级目标）+ 触发翻转记录版本。 */
    void onRecovered(EjectionTarget target, long version);

    /** 流终止（对端断流/错误/完成）；此后该订阅不再回调。 */
    void onClosed();
}
