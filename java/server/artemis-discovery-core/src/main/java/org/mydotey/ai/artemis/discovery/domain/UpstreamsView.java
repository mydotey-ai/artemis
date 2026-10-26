package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/**
 * 管理面上游状态视图（D-A4/D-A5）：registry 来源新鲜度 + 摘除规则源状态。管理面不做
 * readiness 门控，运维按此自解释数据是否可能陈旧。phase-5：appliedVersion 为本节点
 * 已应用规则集版本（-1 = 尚未应用；与 management 生效水位对照的排障出口）。
 * phase-2：routeRules 节为路由规则流对照（独立状态与版本域）。
 */
public record UpstreamsView(
        List<UpstreamStatus> upstreams,
        boolean managementEnabled,
        ManagementState managementState,
        long managementAppliedVersion,
        RouteRuleSync routeRules) {

    /** 路由规则流同步对照（排障：本节点应用版本 ↔ management 水位）。 */
    public record RouteRuleSync(ManagementState state, long appliedVersion) {}

    public UpstreamsView {
        upstreams = List.copyOf(upstreams);
    }
}
