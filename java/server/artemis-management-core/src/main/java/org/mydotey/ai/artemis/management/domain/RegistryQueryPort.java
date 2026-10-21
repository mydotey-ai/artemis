package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * registry 只读事实查询出站端口（gRPC 实现由 server 模块提供，ADR-016）。
 */
public interface RegistryQueryPort {

    /** 服务列表（namespace null/空白 = 全部）。 */
    List<ServiceSummary> listServices(String namespace);

    /** 实例快照；未注册 service 返回空列表。 */
    List<Instance> getInstances(ServiceKey key);

    /** 节点级保护统计 + 租约明细（namespace/service 均可选）。 */
    LeaseStatus leaseStatus(String namespace, String service);

    /** 自我保护状态（节点级统计 + 按服务明细；默认仅保护中服务）。 */
    SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal);

    /** registry 配置状态（敏感值已在 registry 侧脱敏）。 */
    List<ConfigProperty> configStatus();
}
