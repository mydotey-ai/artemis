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
 *
 * <p>逻辑实例源（phase-4）：{@code logicalSource} 非空（配置了 management）时以保留
 * cluster {@code logical} 并入同一编排（复用 ProjectionSynchronizer 全套）；不参与
 * readiness 门控（0..1 治理增强，fail-open），上游 cluster id 撞保留名启动失败。
 */
public final class UpstreamSynchronizers implements AutoCloseable {

    /** 逻辑源不门控 readiness：onSynced 无操作（新鲜度仍经 UpstreamStatuses 上报）。 */
    private static final ProjectionSyncListener LOGICAL_SYNC_LISTENER = new ProjectionSyncListener() {
        @Override
        public void onSynced() {
            // 无操作：逻辑实例是 0..1 治理增强，不参与 readiness 全量门
        }
    };

    private final List<GrpcProjectionSource> sources;
    private final List<ProjectionSynchronizer> synchronizers;
    private final ProjectionSynchronizer logicalSynchronizer;

    public UpstreamSynchronizers(
            StringProperties properties,
            UpstreamConfig upstreamConfig,
            UpstreamAuthConfig auth,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            DiscoveryReadinessGate readinessGate,
            UpstreamStatuses upstreamStatuses,
            GrpcLogicalInstanceSource logicalSource) {
        sources = new ArrayList<>(upstreamConfig.upstreams().size());
        synchronizers = new ArrayList<>(upstreamConfig.upstreams().size());
        for (UpstreamConfig.Upstream upstream : upstreamConfig.upstreams()) {
            if (GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID.equals(upstream.clusterId())) {
                throw new IllegalStateException("upstream cluster id '"
                        + GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID
                        + "' is reserved for logical instances");
            }
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
        logicalSynchronizer = logicalSource == null
                ? null
                : new ProjectionSynchronizer(
                        properties,
                        logicalSource,
                        registry,
                        subscriptionManager,
                        GrpcLogicalInstanceSource.LOGICAL_CLUSTER_ID,
                        LOGICAL_SYNC_LISTENER,
                        upstreamStatuses);
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
        if (logicalSynchronizer != null) {
            logicalSynchronizer.start();
        }
    }

    @Override
    public void close() {
        if (logicalSynchronizer != null) {
            logicalSynchronizer.close(); // 先停同步线程
        }
        synchronizers.forEach(ProjectionSynchronizer::close); // 先停同步线程
        sources.forEach(GrpcProjectionSource::close); // 再关 channel
        sources.forEach(GrpcProjectionSource::close);
    }
}
