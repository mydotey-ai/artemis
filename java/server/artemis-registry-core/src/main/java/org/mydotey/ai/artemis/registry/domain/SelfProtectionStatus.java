package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 自我保护状态查询快照（ADR-017）：节点级统计 + 按服务明细。
 *
 * @param trackedServices     有本地心跳窗口的服务数
 * @param protectingServices  当前保护中服务数
 * @param node                节点级窗口统计（v0.1 语义）
 * @param services            按 (namespace, service) 升序的服务明细
 * @param truncated           服务明细是否因单次上限被截断
 */
public record SelfProtectionStatus(
        long trackedServices,
        long protectingServices,
        SelfProtectionStats node,
        List<ServiceProtectionStatus> services,
        boolean truncated) {

    public SelfProtectionStatus {
        services = services == null ? List.of() : List.copyOf(services);
    }
}
