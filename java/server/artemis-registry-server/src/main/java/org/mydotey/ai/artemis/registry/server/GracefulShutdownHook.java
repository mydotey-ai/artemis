package org.mydotey.ai.artemis.registry.server;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 优雅停机序列（v0.2 phase-6）：context close 第一时机标记 self DOWN → 排水等待 → 终刷快照，
 * 完成后交还 Spring 关闭 gRPC 端口（已建心跳流随端口关闭断开，SDK 凭容灾换址）。
 *
 * <p>{@link ContextClosedEvent} 发布先于 SmartLifecycle stop 与 bean 销毁——gRPC server 生命周期
 * 由 starter 以 MAX_VALUE phase 管理，事件监听是保证序列先于端口关闭的唯一先行落点（停机标记
 * 与注销一样，是程序还可用时的主动动作）。
 */
@Slf4j
public final class GracefulShutdownHook implements ApplicationListener<ContextClosedEvent> {

    static final String KEY_DRAIN_MS = "artemis.registry.shutdown.drainMs";

    private static final long DEFAULT_DRAIN_MS = 10_000;

    private final ReadinessGate readinessGate;
    private final RegistryService registryService;
    private final ClusterMembers clusterMembers;
    private final Property<String, Long> drainMs;
    private final AtomicBoolean executed = new AtomicBoolean();

    public GracefulShutdownHook(
            ReadinessGate readinessGate,
            RegistryService registryService,
            ClusterMembers clusterMembers,
            StringProperties properties) {
        this.readinessGate = readinessGate;
        this.registryService = registryService;
        this.clusterMembers = clusterMembers;
        this.drainMs = properties.getLongProperty(KEY_DRAIN_MS, new RangeValueConfig<>(DEFAULT_DRAIN_MS, 0L, 600_000L));
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!executed.compareAndSet(false, true)) {
            return; // 重复 close 事件幂等
        }
        String nodeId = clusterMembers.self().map(ClusterMember::nodeId).orElse("");
        // 终刷仅在节点曾就绪时执行：启动数据源未取得（分支① 同步重试中/启动失败）时注册表为空或残缺，
        // 写盘会覆盖可回退的旧快照——与快照调度器「not ready 不落盘」守卫同一理由
        boolean wasReady = readinessGate.isReady();
        readinessGate.markDown();
        long drain = drainMs.getValue();
        log.info("graceful shutdown: node '{}' marked DOWN, draining for {}ms", nodeId, drain);
        if (drain > 0) {
            try {
                // 停机排水的固定等待（非轮询）；中断视为进程要求立即退出，提前进入终刷
                Thread.sleep(drain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!wasReady) {
            log.info("graceful shutdown: node '{}' never became ready, skipping final snapshot", nodeId);
            return;
        }
        try {
            registryService.writeSnapshot();
            log.info("graceful shutdown: final snapshot written");
        } catch (RuntimeException e) {
            log.warn("graceful shutdown: final snapshot failed, restart falls back to peer sync / TTL", e);
        }
    }
}
