package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 逻辑实例订阅者端口（逻辑实例流服务端回调，模式同 {@link EjectionRuleSubscriber}）：
 * 单订阅者内串行调用。
 */
public interface LogicalInstanceSubscriber {

    /** 订阅建立即全量快照（替换语义，含空集）。 */
    void onSnapshot(List<LogicalInstance> instances);

    /** 增量：新增/覆盖（upsert 语义，内容变化才产生）。 */
    void onUpserted(LogicalInstance instance);

    /** 增量：删除（命中才产生）。 */
    void onDeleted(ServiceKey service, String instanceId);

    /** 断流（服务端注销；此后不再回调）。 */
    void close();
}
