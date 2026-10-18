package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 状态 API 数据源：自我保护统计 + 租约明细（弱一致读）。
 * truncated 表示查询结果被服务端单次返回上限截断，调用方应按 service 分别查询。
 */
public record LeaseStatus(SelfProtectionStats protection, List<LeaseDetail> leases, boolean truncated) {

    public LeaseStatus {
        leases = List.copyOf(leases);
    }
}
