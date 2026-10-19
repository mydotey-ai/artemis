package org.mydotey.ai.artemis.management.domain;

import java.util.List;
import java.util.Map;

/**
 * 摘除记录端口（内存态实现由 infrastructure 提供）。
 */
public interface EjectionStore {

    /** 幂等：已存在则保留首条记录并返回之。 */
    EjectionRecord eject(EjectionRecord record);

    /** 幂等：未摘除返回 false。 */
    boolean recover(InstanceKey instance);

    /** 指定 service 的摘除记录，按 instanceId 升序。 */
    List<EjectionRecord> findByService(ServiceKey service);

    /** 按 service 聚合记录数（单次遍历，供服务列表）。 */
    Map<ServiceKey, Long> countByService();
}
