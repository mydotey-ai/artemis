package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/** 自我保护状态查询结果（节点级统计 + 按服务明细，registry 透传）。 */
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
