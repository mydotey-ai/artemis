package org.mydotey.ai.artemis.discovery.application;

import java.util.List;
import org.mydotey.ai.artemis.discovery.domain.ChecksumResponse;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatus;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;

/**
 * 对账用例（D-F1/D-F3）：选择器校验（信任边界一次，语义同查询）→ checksum 表（过滤后
 * 视图口径，D-E1）+ 新鲜度快照。校验失败抛 {@link IllegalArgumentException}，由 proto 门面
 * 转契约错误码 INVALID_ARGUMENT。
 */
public final class DiscoveryReconciliationService {

    private final ProjectionRegistry registry;
    private final DiscoveryFilterChain chain;
    private final UpstreamStatuses upstreamStatuses;

    public DiscoveryReconciliationService(ProjectionRegistry registry, UpstreamStatuses upstreamStatuses) {
        this(registry, DiscoveryFilterChain.empty(), upstreamStatuses);
    }

    public DiscoveryReconciliationService(
            ProjectionRegistry registry, DiscoveryFilterChain chain, UpstreamStatuses upstreamStatuses) {
        this.registry = registry;
        this.chain = chain;
        this.upstreamStatuses = upstreamStatuses;
    }

    public ChecksumResponse checksum(DiscoverySelector selector) {
        selector.requireValid();
        // 先快照后读表（code-review FIX-1，同 DiscoveryQueryService）：防「陈旧表 + SYNCED」
        // 使 SDK 对账静默跳过全量拉
        List<UpstreamStatus> upstreams = upstreamStatuses.snapshot(selector);
        return new ChecksumResponse(registry.checksums(selector, chain), upstreams);
    }
}
