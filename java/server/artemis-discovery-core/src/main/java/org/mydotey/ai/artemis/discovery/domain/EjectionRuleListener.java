package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/**
 * 摘除规则流监听端口（gRPC 回调线程调用，实现方须非阻塞：入队移交，不做应用逻辑）。
 * 事件语义（D-E7）：快照 = 替换（含空集）；增量 = 作用于最新集；流内按产生序。
 */
public interface EjectionRuleListener {

    /** 首条全量快照（含空集，替换语义）。 */
    void onSnapshot(List<EjectionRule> rules);

    /** 增量：摘除。 */
    void onEjected(EjectionRule rule);

    /** 增量：恢复。 */
    void onRecovered(ServiceKey service, String instanceId);

    /** 流终止（对端断流/错误/完成）；此后该订阅不再回调。 */
    void onClosed();
}
