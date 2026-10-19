package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 租约与保护统计（状态 API 透传；truncated 表示 registry 侧单次上限截断）。
 */
public record LeaseStatus(SelfProtectionStats protection, List<LeaseDetail> leases, boolean truncated) {

    public LeaseStatus {
        leases = leases == null ? List.of() : List.copyOf(leases);
    }
}
