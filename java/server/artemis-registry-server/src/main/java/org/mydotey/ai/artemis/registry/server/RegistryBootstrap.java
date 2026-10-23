package org.mydotey.ai.artemis.registry.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerBootstrapSource;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.springframework.beans.factory.InitializingBean;

/**
 * 启动编排（架构 §4.1 三分支）：单节点同步回放快照（v0.1 语义）；集群模式异步执行
 * ①「存在已就绪 peer → 全量同步成功才 ready」——首轮立即、失败按全抖动指数退避重试，
 * 每轮尝试全部 UP 候选；无 UP peer 时回退本地快照（分支②/③）。
 *
 * <p>异步执行让端口先绑定：外部写流量由 readiness 拦截，节点状态 API 可观察 STARTING。
 * 未就绪期间不回退本地快照（分支①已确认存在数据源）；快照落盘由装配层按 readiness 门控。
 */
@Slf4j
public final class RegistryBootstrap implements InitializingBean, AutoCloseable {

    static final String KEY_RETRY_INITIAL_MS = "artemis.registry.bootstrap.retryInitialMs";
    static final String KEY_RETRY_MAX_MS = "artemis.registry.bootstrap.retryMaxMs";
    static final String KEY_STATUS_TIMEOUT_MS = "artemis.registry.bootstrap.statusTimeoutMs";

    private static final long DEFAULT_RETRY_INITIAL_MS = 1_000;
    private static final long DEFAULT_RETRY_MAX_MS = 10_000;
    private static final long DEFAULT_STATUS_TIMEOUT_MS = 1_000;

    private final RegistryService registryService;
    private final ReadinessGate readinessGate;
    private final ClusterMembers clusterMembers;
    private final PeerBootstrapSource peerBootstrapSource;
    private final Property<String, Long> retryInitialMs;
    private final Property<String, Long> retryMaxMs;
    private final Property<String, Long> statusTimeoutMs;
    private final Counter attempts;
    private final Counter failures;
    private final Counter syncedInstances;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("artemis-bootstrap").daemon(true).factory());

    /** 退避阶梯（scheduler 线程独占）。 */
    private int failedRounds;

    /**
     * 是否已确认过「存在已就绪 peer」（scheduler 线程独占）。一旦确认，本轮启动必须完成 peer
     * 同步——后续轮次即使 peer 暂时全部不可达也不降级本地快照。
     */
    private boolean peerSyncRequired;

    public RegistryBootstrap(
            RegistryService registryService,
            ReadinessGate readinessGate,
            ClusterMembers clusterMembers,
            PeerBootstrapSource peerBootstrapSource,
            StringProperties properties,
            MeterRegistry meterRegistry) {
        this.registryService = registryService;
        this.readinessGate = readinessGate;
        this.clusterMembers = clusterMembers;
        this.peerBootstrapSource = peerBootstrapSource;
        this.retryInitialMs = properties.getLongProperty(
                KEY_RETRY_INITIAL_MS, new RangeValueConfig<>(DEFAULT_RETRY_INITIAL_MS, 100L, 600_000L));
        this.retryMaxMs = properties.getLongProperty(
                KEY_RETRY_MAX_MS, new RangeValueConfig<>(DEFAULT_RETRY_MAX_MS, 100L, 600_000L));
        this.statusTimeoutMs = properties.getLongProperty(
                KEY_STATUS_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_STATUS_TIMEOUT_MS, 100L, 60_000L));
        this.attempts = Counter.builder("artemis.registry.bootstrap.attempts")
                .description("Bootstrap sync attempts")
                .register(meterRegistry);
        this.failures = Counter.builder("artemis.registry.bootstrap.failures")
                .description("Failed bootstrap sync rounds")
                .register(meterRegistry);
        this.syncedInstances = Counter.builder("artemis.registry.bootstrap.synced.instances")
                .description("Instances applied from peer bootstrap")
                .register(meterRegistry);
    }

    @Override
    public void afterPropertiesSet() {
        if (clusterMembers.self().isEmpty()) {
            // 单节点：保持 v0.1 同步回放语义（分支②/③）
            int restored = registryService.restoreFromSnapshot();
            readinessGate.markReady();
            log.info("single-node bootstrap complete, restored {} instances, server ready", restored);
            return;
        }
        scheduler.execute(this::attempt);
    }

    /** 单轮启动数据源判定（package-private 供单测同步驱动）。 */
    void attempt() {
        if (readinessGate.isReady()) {
            return;
        }
        if (readinessGate.isDown()) {
            // 优雅停机中：不再同步、不再回放快照（与终刷快照并发替换注册表会互相覆盖）
            return;
        }
        attempts.increment();
        try {
            List<ClusterMember> candidates = readyPeers();
            if (candidates.isEmpty()) {
                if (peerSyncRequired) {
                    // 已确认过数据源存在：peer 暂时全部不可达时保持 not ready，不降级本地快照
                    failures.increment();
                    scheduleRetry();
                    return;
                }
                int restored = registryService.restoreFromSnapshot();
                readinessGate.markReady();
                log.info("no ready peer found, restored {} instances from local snapshot, server ready", restored);
                return;
            }
            peerSyncRequired = true;
            for (ClusterMember peer : candidates) {
                try {
                    List<ServiceInstances> snapshot = peerBootstrapSource.bootstrap(peer);
                    int applied = registryService.applyBootstrap(snapshot);
                    syncedInstances.increment(applied);
                    readinessGate.markReady();
                    log.info(
                            "bootstrap from peer {} complete, applied {} instances, server ready",
                            peer.nodeId(),
                            applied);
                    return;
                } catch (RuntimeException e) {
                    log.warn("bootstrap from peer {} failed", peer.nodeId(), e);
                }
            }
            failures.increment();
            scheduleRetry();
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("bootstrap attempt failed", e);
            scheduleRetry();
        }
    }

    /** 顺序查询 peer 状态，收集严格 UP 且自报 nodeId 与配置一致的候选。 */
    private List<ClusterMember> readyPeers() {
        List<ClusterMember> candidates = new ArrayList<>();
        String selfNodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse(null);
        long timeoutMs = statusTimeoutMs.getValue();
        for (ClusterMember member : clusterMembers.members()) {
            if (member.nodeId().equals(selfNodeId)) {
                continue;
            }
            try {
                NodeStatus status = peerBootstrapSource.status(member, timeoutMs);
                if (!member.nodeId().equals(status.nodeId())) {
                    log.warn(
                            "peer {} returned node id {}, but cluster config says {}",
                            member.nodeId(),
                            status.nodeId(),
                            member.nodeId());
                    continue;
                }
                if (status.state() == NodeState.UP) {
                    candidates.add(member);
                }
            } catch (RuntimeException e) {
                log.debug("peer {} status probe failed", member.nodeId(), e);
            }
        }
        return candidates;
    }

    private void scheduleRetry() {
        if (scheduler.isShutdown()) {
            return;
        }
        long delayMs = retryDelayMs();
        log.warn("bootstrap sync failed, retrying in {}ms", delayMs);
        scheduler.schedule(this::attempt, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 全抖动指数退避：random(0, min(max, initial × 2^failedRounds))。 */
    private long retryDelayMs() {
        long initial = retryInitialMs.getValue();
        long max = retryMaxMs.getValue();
        int exponent = Math.min(failedRounds++, 20);
        long cap = Math.min(max, initial << exponent);
        return cap <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
