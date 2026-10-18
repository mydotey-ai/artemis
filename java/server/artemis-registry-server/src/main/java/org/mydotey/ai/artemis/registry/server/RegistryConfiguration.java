package org.mydotey.ai.artemis.registry.server;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryProtoFacade;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistryServiceImpl;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.SelfProtectionConfig;
import org.mydotey.ai.artemis.registry.domain.SnapshotScheduler;
import org.mydotey.ai.artemis.registry.infrastructure.FileRegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.infrastructure.ScheduledLeaseEvictionScheduler;
import org.mydotey.ai.artemis.registry.infrastructure.ScheduledSnapshotScheduler;
import org.mydotey.ai.artemis.registry.server.grpc.ReadinessInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 装配层（IoC）：领域端口实现注入 + 应用服务/门面/清理与快照调度/启动回放组合。
 * 端口默认实现随 phase 递补：TTL 来源 phase-4 换 SCF Property、变更出口 v0.2 接集群复制扇出。
 */
@Configuration(proxyBeanMethods = false)
public class RegistryConfiguration {

    /** 默认租约 TTL（D6；phase-4 SCF 化动态生效）。 */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    /** 默认清理周期（D6；phase-4 SCF 化）。 */
    static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofSeconds(1);

    /** 默认自我保护参数（D2，语义继承 legacy §2.3；phase-4 SCF 化）。 */
    static final SelfProtectionConfig DEFAULT_SELF_PROTECTION =
            new SelfProtectionConfig(Duration.ofSeconds(10), 0.85, 50);

    /** 默认快照落盘周期（D7；phase-4 SCF 化）。 */
    static final Duration DEFAULT_SNAPSHOT_INTERVAL = Duration.ofMinutes(5);

    /** 默认快照时效阈值（D10；phase-4 SCF 化）。 */
    static final Duration DEFAULT_SNAPSHOT_MAX_AGE = Duration.ofHours(1);

    /** 默认快照路径（guidelines §10.2 静态项）。 */
    static final String DEFAULT_SNAPSHOT_PATH = "data/registry-snapshot.bin";

    @Bean
    Clock clock() {
        // 壁挂钟：跨进程时间戳（serverTimeMs、快照落盘时间戳），不参与进程内时长判定（D14）；
        // 唯一例外是快照回放的跨重启时效判定（D10）——必须用墙钟，两边进程的单调钟不可比
        return Clock.systemUTC();
    }

    @Bean
    MonotonicClock monotonicClock() {
        // 单调钟：一切时长判定（续约/过期/事件时刻/保护窗口，D14）——不受 NTP 校时影响
        return () -> System.nanoTime() / 1_000_000;
    }

    @Bean
    LeaseTtlSource leaseTtlSource() {
        return () -> DEFAULT_LEASE_TTL;
    }

    /** 变更事件出口：v0.2 集群复制前为 no-op（进程内语义已由单测/IT 直接注入收集器验证）。 */
    @Bean
    ChangeListener changeListener() {
        return change -> {};
    }

    @Bean
    SelfProtection selfProtection(MonotonicClock monotonicClock) {
        return new SelfProtection(DEFAULT_SELF_PROTECTION, monotonicClock);
    }

    @Bean
    RegistrySnapshotStore registrySnapshotStore() {
        // 常量默认（配置一律走 SCF，phase-4 动态化）；测试以 @Primary 测试 bean 覆盖路径
        return new FileRegistrySnapshotStore(Path.of(DEFAULT_SNAPSHOT_PATH));
    }

    @Bean
    RegistryService registryService(
            LeaseTtlSource leaseTtl,
            Clock clock,
            MonotonicClock monotonicClock,
            ChangeListener changeListener,
            SelfProtection selfProtection,
            RegistrySnapshotStore snapshotStore) {
        return new RegistryServiceImpl(
                leaseTtl,
                clock,
                monotonicClock,
                changeListener,
                selfProtection,
                snapshotStore,
                DEFAULT_SNAPSHOT_MAX_AGE);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOn("registryBootstrap") // 首轮清理须在快照回放完成后启动（恢复中也由聚合锁串行，此为显式意图）
    LeaseEvictionScheduler leaseEvictionScheduler(RegistryService registryService) {
        return new ScheduledLeaseEvictionScheduler(DEFAULT_CLEANUP_INTERVAL, registryService::evictExpired);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    SnapshotScheduler snapshotScheduler(RegistryService registryService) {
        return new ScheduledSnapshotScheduler(DEFAULT_SNAPSHOT_INTERVAL, registryService::writeSnapshot);
    }

    @Bean
    ReadinessGate readinessGate() {
        return new ReadinessGate();
    }

    @Bean
    RegistryBootstrap registryBootstrap(RegistryService registryService, ReadinessGate readinessGate) {
        return new RegistryBootstrap(registryService, readinessGate);
    }

    @Bean
    ReadinessInterceptor readinessInterceptor(ReadinessGate readinessGate) {
        return new ReadinessInterceptor(readinessGate);
    }

    @Bean
    RegistryProtoFacade registryProtoFacade(RegistryService registryService) {
        return new RegistryProtoFacade(registryService);
    }
}
