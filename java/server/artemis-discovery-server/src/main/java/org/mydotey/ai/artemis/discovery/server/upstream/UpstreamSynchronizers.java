package org.mydotey.ai.artemis.discovery.server.upstream;

import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSyncListener;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;
import org.mydotey.ai.artemis.discovery.infrastructure.ProjectionSynchronizer;
import org.mydotey.scf.facade.StringProperties;

/**
 * 多上游同步编排（phase-5，ADR-002/D2：聚合只发生在本地）：per cluster 一对
 * (source, synchronizer)——投影分表、上游状态、退避重连各自独立（跨源隔离）；
 * N 同步线程经 {@link SubscriptionManager#runWithFanout} 共享表锁串行扇出（per-cluster
 * 变更序保持，跨 cluster 无顺序约束）。readiness 为全量门（{@link DiscoveryReadinessGate}）。
 */
public final class UpstreamSynchronizers implements AutoCloseable {

    private final List<GrpcProjectionSource> sources;
    private final List<ProjectionSynchronizer> synchronizers;

    public UpstreamSynchronizers(
            StringProperties properties,
            UpstreamConfig upstreamConfig,
            UpstreamAuthConfig auth,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            DiscoveryReadinessGate readinessGate,
            UpstreamStatuses upstreamStatuses) {
        sources = new ArrayList<>(upstreamConfig.upstreams().size());
        synchronizers = new ArrayList<>(upstreamConfig.upstreams().size());
        for (UpstreamConfig.Upstream upstream : upstreamConfig.upstreams()) {
            // ponytail: 全部集群共享同一 UpstreamAuthConfig，per-cluster 凭证按真实部署需求再加
            GrpcProjectionSource source = new GrpcProjectionSource(properties, upstream, auth);
            sources.add(source);
            synchronizers.add(new ProjectionSynchronizer(
                    properties,
                    source,
                    registry,
                    subscriptionManager,
                    upstream.clusterId(),
                    syncedListener(upstream.clusterId(), readinessGate),
                    upstreamStatuses));
        }
    }

    private static ProjectionSyncListener syncedListener(String clusterId, DiscoveryReadinessGate readinessGate) {
        return new ProjectionSyncListener() {
            @Override
            public void onSynced() {
                readinessGate.markSynced(clusterId);
            }
        };
    }

    public void start() {
        synchronizers.forEach(ProjectionSynchronizer::start);
    }

    @Override
    public void close() {
        synchronizers.forEach(ProjectionSynchronizer::close); // 先停同步线程
        sources.forEach(GrpcProjectionSource::close); // 再关 channel
    }
}
