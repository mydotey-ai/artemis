package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 逻辑实例存储端口：只存取；读-改-写与幂等判定在应用层锁内串行
 * （免双方言 upsert SQL，ejectionLock 先例）。
 */
public interface LogicalInstanceStore {

    LogicalInstance find(ServiceKey service, String instanceId);

    /** 全量（流快照与列表用）。 */
    List<LogicalInstance> findAll();

    /** upsert（全量覆盖语义）；返回 previous（null = 新增）。 */
    LogicalInstance upsert(LogicalInstance instance);

    /** 删除；返回 removed（null = 缺失）。 */
    LogicalInstance delete(ServiceKey service, String instanceId);
}
