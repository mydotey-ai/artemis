package org.mydotey.ai.artemis.registry.server;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryProtoFacade;
import org.mydotey.ai.artemis.registry.application.RegistryQueryProtoFacade;
import org.mydotey.ai.artemis.registry.application.RegistryQueryService;
import org.mydotey.ai.artemis.registry.application.RegistryQueryServiceImpl;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistryServiceImpl;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;
import org.mydotey.ai.artemis.registry.application.ReplicationService;
import org.mydotey.ai.artemis.registry.application.ReplicationServiceImpl;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
import org.mydotey.ai.artemis.registry.domain.PeerReplicator;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.SnapshotScheduler;
import org.mydotey.ai.artemis.registry.infrastructure.FileRegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.infrastructure.ReplicationFanout;
import org.mydotey.ai.artemis.registry.infrastructure.ScheduledLeaseEvictionScheduler;
import org.mydotey.ai.artemis.registry.infrastructure.ScheduledSnapshotScheduler;
import org.mydotey.ai.artemis.registry.infrastructure.StaticClusterMembers;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.mydotey.ai.artemis.registry.server.config.RegistryConfigurationSources;
import org.mydotey.ai.artemis.registry.server.grpc.GrpcAuthInterceptor;
import org.mydotey.ai.artemis.registry.server.grpc.ReadinessInterceptor;
import org.mydotey.ai.artemis.registry.server.grpc.RegistryQueryGrpcService;
import org.mydotey.ai.artemis.registry.server.grpc.ReplicationGrpcService;
import org.mydotey.ai.artemis.registry.server.health.ReadinessHealthIndicator;
import org.mydotey.ai.artemis.registry.server.http.HttpBasicAuthFilter;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

/**
 * 装配层（IoC）：SCF 配置装配（source → manager → properties）+ 领域端口实现注入 +
 * 应用服务/门面/清理与快照调度/启动回放/认证/可观测组合。
 */
@Configuration(proxyBeanMethods = false)
public class RegistryConfiguration {

    @Bean
    ConfigurationManager configurationManager(Environment environment) {
        return RegistryConfigurationSources.manager(environment);
    }

    /** 集中定义的只有 manager 与 properties 门面；具体 property 由各使用类在类内定义。 */
    @Bean
    StringProperties stringProperties(ConfigurationManager configurationManager) {
        return new StringProperties(configurationManager);
    }

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

    /**
     * 变更事件出口：多节点成员身份（静态配置，装配期判定）→ 扇出管线（运行时类有 public close，
     * inferred destroy 生效）；单节点 → no-op（零开销，纯续约 RENEW 事件被吞掉，语义与 v0.1 一致）。
     */
    @Bean
    ChangeListener changeListener(
            StringProperties properties,
            ClusterMembers clusterMembers,
            PeerReplicator peerReplicator,
            MeterRegistry meterRegistry) {
        if (clusterMembers.self().isEmpty()) {
            return change -> {};
        }
        ReplicationFanout fanout = new ReplicationFanout(properties, clusterMembers, peerReplicator, meterRegistry);
        fanout.start();
        return fanout;
    }

    @Bean
    SelfProtection selfProtection(
            StringProperties properties, MeterRegistry meterRegistry, MonotonicClock monotonicClock) {
        return new SelfProtection(properties, meterRegistry, monotonicClock);
    }

    @Bean
    RegistrySnapshotStore registrySnapshotStore(StringProperties properties) {
        return new FileRegistrySnapshotStore(properties);
    }

    @Bean
    ClusterMembers clusterMembers(StringProperties properties) {
        return new StaticClusterMembers(properties);
    }

    /** 注册表聚合共享 bean：数据面应用服务与内部只读查询共用同一实例。 */
    @Bean
    InstanceRegistry instanceRegistry(
            StringProperties properties, MonotonicClock monotonicClock, ChangeListener changeListener) {
        return new InstanceRegistry(properties, monotonicClock, changeListener);
    }

    @Bean
    RegistryService registryService(
            InstanceRegistry registry,
            StringProperties properties,
            Clock clock,
            SelfProtection selfProtection,
            RegistrySnapshotStore snapshotStore,
            MeterRegistry meterRegistry) {
        return new RegistryServiceImpl(registry, properties, clock, selfProtection, snapshotStore, meterRegistry);
    }

    @Bean
    RegistryQueryService registryQueryService(
            InstanceRegistry registry, SelfProtection selfProtection, StringProperties properties) {
        return new RegistryQueryServiceImpl(registry, selfProtection, properties);
    }

    @Bean
    RegistryQueryProtoFacade registryQueryProtoFacade(RegistryQueryService registryQueryService) {
        return new RegistryQueryProtoFacade(registryQueryService);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @DependsOn("registryBootstrap") // 首轮清理须在快照回放完成后启动（恢复中也由聚合锁串行，此为显式意图）
    LeaseEvictionScheduler leaseEvictionScheduler(RegistryService registryService, StringProperties properties) {
        return new ScheduledLeaseEvictionScheduler(properties, registryService::evictExpired);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    SnapshotScheduler snapshotScheduler(RegistryService registryService, StringProperties properties) {
        return new ScheduledSnapshotScheduler(properties, registryService::writeSnapshot);
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

    @Bean
    RegistryQueryGrpcService registryQueryGrpcService(RegistryQueryProtoFacade facade) {
        return new RegistryQueryGrpcService(facade);
    }

    @Bean
    ReplicationService replicationService(
            ClusterMembers clusterMembers,
            ReadinessGate readinessGate,
            InstanceRegistry registry,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new ReplicationServiceImpl(clusterMembers, readinessGate, registry, clock, meterRegistry);
    }

    @Bean
    ReplicationProtoFacade replicationProtoFacade(ReplicationService replicationService) {
        return new ReplicationProtoFacade(replicationService);
    }

    @Bean
    ReplicationGrpcService replicationGrpcService(ReplicationProtoFacade facade) {
        return new ReplicationGrpcService(facade);
    }

    @Bean
    AuthConfig authConfig(StringProperties properties) {
        return new AuthConfig(properties);
    }

    @Bean
    GrpcAuthInterceptor grpcAuthInterceptor(AuthConfig authConfig) {
        return new GrpcAuthInterceptor(authConfig);
    }

    @Bean
    FilterRegistrationBean<HttpBasicAuthFilter> httpBasicAuthFilter(AuthConfig authConfig) {
        FilterRegistrationBean<HttpBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new HttpBasicAuthFilter(authConfig));
        registration.addUrlPatterns("/*");
        registration.setName("httpBasicAuthFilter");
        return registration;
    }

    @Bean
    ReadinessHealthIndicator registryReadinessHealthIndicator(ReadinessGate readinessGate) {
        return new ReadinessHealthIndicator(readinessGate);
    }
}
