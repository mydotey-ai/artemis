package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import java.util.Optional;
import org.mydotey.ai.artemis.registry.domain.ConfigProperty;
import org.mydotey.ai.artemis.registry.domain.LeaseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.ServiceSummary;

/**
 * 内部只读查询用例（management-service 消费，ADR-016）：registry 只返回事实，不感知摘除。
 * 输出排序稳定：服务与租约按 (namespace, service, instanceId)；配置按 key 升序。
 */
public interface RegistryQueryService {

    /** 服务列表（namespace null/空白 = 全部）。 */
    List<ServiceSummary> listServices(String namespace);

    /** 实例快照（注册表事实）；未注册 service 返回 empty。 */
    Optional<ServiceInstances> getInstances(ServiceKey key);

    /**
     * 自我保护统计 + 租约明细（namespace/service 均可选，空 = 全部）。
     * 结果按 (namespace, service, instanceId) 排序；超过单次上限时截断并标记 truncated。
     */
    LeaseStatus leaseStatus(String namespace, String service);

    /** 已创建配置项 + 来源；敏感值已脱敏。 */
    List<ConfigProperty> configStatus();
}
