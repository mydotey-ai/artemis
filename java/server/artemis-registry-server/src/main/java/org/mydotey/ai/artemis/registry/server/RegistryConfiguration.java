package org.mydotey.ai.artemis.registry.server;

import java.time.Clock;
import java.time.Duration;
import org.mydotey.ai.artemis.registry.application.RegistryProtoFacade;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistryServiceImpl;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
import org.mydotey.ai.artemis.registry.infrastructure.ScheduledLeaseEvictionScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配层（IoC）：领域端口实现注入 + 应用服务/门面/清理调度组合。
 * 端口默认实现随 phase 递补：TTL 来源 phase-4 换 SCF Property、变更出口 v0.2 接集群复制扇出。
 */
@Configuration(proxyBeanMethods = false)
public class RegistryConfiguration {

    /** 默认租约 TTL（D6；phase-4 SCF 化动态生效）。 */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    /** 默认清理周期（D6；phase-4 SCF 化）。 */
    static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofSeconds(1);

    @Bean
    Clock clock() {
        // 壁挂钟：仅跨进程时间戳（serverTimeMs），不参与任何时长判定（D14）
        return Clock.systemUTC();
    }

    @Bean
    MonotonicClock monotonicClock() {
        // 单调钟：一切时长判定（续约/过期/事件时刻，D14）——不受 NTP 校时影响
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
    RegistryService registryService(
            LeaseTtlSource leaseTtl, Clock clock, MonotonicClock monotonicClock, ChangeListener changeListener) {
        return new RegistryServiceImpl(leaseTtl, clock, monotonicClock, changeListener);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    LeaseEvictionScheduler leaseEvictionScheduler(RegistryService registryService) {
        return new ScheduledLeaseEvictionScheduler(DEFAULT_CLEANUP_INTERVAL, registryService::evictExpired);
    }

    @Bean
    RegistryProtoFacade registryProtoFacade(RegistryService registryService) {
        return new RegistryProtoFacade(registryService);
    }
}
