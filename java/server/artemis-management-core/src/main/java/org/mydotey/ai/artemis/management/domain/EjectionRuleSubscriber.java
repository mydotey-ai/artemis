package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 摘除规则订阅者端口（规则流服务端回调，D-E7/D-E10）：单订阅者内串行调用。
 */
public interface EjectionRuleSubscriber {

    /** 订阅建立即全量快照（替换语义，含空集）。 */
    void onSnapshot(List<EjectionRecord> records);

    /** 增量：摘除。 */
    void onEjected(EjectionRecord record);

    /** 增量：恢复（四级目标；仅生效态翻转时产生，叠加/对消无翻转不推）。 */
    void onRecovered(EjectionTarget target);

    /** 断流（服务端注销；此后不再回调）。 */
    void close();
}
