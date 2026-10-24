package org.mydotey.ai.artemis.discovery.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.QueryRequest;
import org.mydotey.ai.artemis.discovery.domain.QueryResponse;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatus;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;

/**
 * 查询用例：选择器归一/校验（信任边界一次）→ 投影查询 + 新鲜度装饰（D-F4）。
 * 校验失败抛 {@link IllegalArgumentException}，由 proto 门面转契约错误码 INVALID_ARGUMENT。
 */
public final class DiscoveryQueryService {

    private final ProjectionRegistry registry;
    private final UpstreamStatuses upstreamStatuses;

    public DiscoveryQueryService(ProjectionRegistry registry, UpstreamStatuses upstreamStatuses) {
        this.registry = registry;
        this.upstreamStatuses = upstreamStatuses;
    }

    public QueryResponse query(QueryRequest request) {
        Set<ServiceKey> services = new LinkedHashSet<>(request.services());
        DiscoverySelector selector =
                new DiscoverySelector(services, request.labels(), new LinkedHashSet<>(request.clusters()));
        selector.requireValid();
        // 先快照后读表（code-review FIX-1）：与同步线程「先覆盖后 setState(SYNCED)」的发布序
        // 交错只产生保守向（陈旧标签 + 新数据），不出现夸大新鲜度（SYNCED + 陈旧数据）
        List<UpstreamStatus> upstreams = upstreamStatuses.snapshot(selector);
        return new QueryResponse(registry.query(selector), upstreams);
    }
}
