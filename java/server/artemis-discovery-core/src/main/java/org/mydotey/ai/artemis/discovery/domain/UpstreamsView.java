package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/**
 * 管理面上游状态视图（D-A4/D-A5）：registry 来源新鲜度 + 摘除规则源状态。管理面不做
 * readiness 门控，运维按此自解释数据是否可能陈旧。
 */
public record UpstreamsView(
        List<UpstreamStatus> upstreams, boolean managementEnabled, ManagementState managementState) {

    public UpstreamsView {
        upstreams = List.copyOf(upstreams);
    }
}
