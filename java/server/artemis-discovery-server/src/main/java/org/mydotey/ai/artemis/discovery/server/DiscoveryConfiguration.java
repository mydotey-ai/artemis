package org.mydotey.ai.artemis.discovery.server;

import io.micrometer.core.instrument.MeterRegistry;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoFacade;
import org.mydotey.ai.artemis.discovery.application.DiscoveryQueryService;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReconciliationService;
import org.mydotey.ai.artemis.discovery.application.DiscoverySubscriptionService;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ProjectionSyncListener;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;
import org.mydotey.ai.artemis.discovery.infrastructure.ProjectionSynchronizer;
import org.mydotey.ai.artemis.discovery.server.auth.AuthConfig;
import org.mydotey.ai.artemis.discovery.server.config.DiscoveryConfigurationSources;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoveryQueryGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoveryReconciliationGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoverySubscriptionGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.GrpcAuthInterceptor;
import org.mydotey.ai.artemis.discovery.server.grpc.ReadinessInterceptor;
import org.mydotey.ai.artemis.discovery.server.health.DiscoveryReadinessHealthIndicator;
import org.mydotey.ai.artemis.discovery.server.upstream.GrpcProjectionSource;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamAuthConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamConfig;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配层（IoC）：SCF 配置装配 + 投影存储/同步引擎/上游传输 + 查询出口与拦截器组合。 */
@Configuration(proxyBeanMethods = false)
public class DiscoveryConfiguration {

    @Bean
    ConfigurationManager configurationManager(Environment environment) {
        return DiscoveryConfigurationSources.manager(environment);
    }

    /** 集中定义的只有 manager 与 properties 门面；具体 property 由各使用类在类内定义。 */
    @Bean
    StringProperties stringProperties(ConfigurationManager configurationManager) {
        return new StringProperties(configurationManager);
    }

    @Bean
    DiscoveryReadinessGate discoveryReadinessGate() {
        return new DiscoveryReadinessGate();
    }

    @Bean
    ProjectionRegistry projectionRegistry() {
        return new ProjectionRegistry();
    }

    @Bean
    SubscriptionManager subscriptionManager(ProjectionRegistry registry) {
        return new SubscriptionManager(registry);
    }

    /** 上游同步状态表（D-F5）：同步引擎上报、查询/对账响应装饰新鲜度。 */
    @Bean
    UpstreamStatuses upstreamStatuses() {
        return new UpstreamStatuses();
    }

    @Bean
    UpstreamConfig upstreamConfig(StringProperties properties) {
        return new UpstreamConfig(properties);
    }

    @Bean
    UpstreamAuthConfig upstreamAuthConfig(StringProperties properties) {
        return new UpstreamAuthConfig(properties);
    }

    /** 上游投影数据源（领域端口 gRPC 实现，channel 池随 close 释放）。 */
    @Bean(destroyMethod = "close")
    GrpcProjectionSource grpcProjectionSource(
            StringProperties properties, UpstreamConfig config, UpstreamAuthConfig auth) {
        return new GrpcProjectionSource(properties, config, auth);
    }

    /** 上游同步引擎：首轮 SYNCED 开 readiness，断连只通知不降（D-D5）；订阅扇出内联同步线程（D-S4/D-S7）；状态转移上报新鲜度表（D-F5）。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    ProjectionSynchronizer projectionSynchronizer(
            StringProperties properties,
            GrpcProjectionSource source,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            UpstreamConfig upstreamConfig,
            DiscoveryReadinessGate readinessGate,
            UpstreamStatuses upstreamStatuses) {
        return new ProjectionSynchronizer(
                properties,
                source,
                registry,
                subscriptionManager,
                upstreamConfig.clusterId(),
                new ProjectionSyncListener() {
                    @Override
                    public void onSynced() {
                        readinessGate.markReady();
                    }
                },
                upstreamStatuses);
    }

    @Bean
    DiscoveryQueryService discoveryQueryService(ProjectionRegistry registry, UpstreamStatuses upstreamStatuses) {
        return new DiscoveryQueryService(registry, upstreamStatuses);
    }

    @Bean
    DiscoverySubscriptionService discoverySubscriptionService(SubscriptionManager manager) {
        return new DiscoverySubscriptionService(manager);
    }

    @Bean
    DiscoveryReconciliationService discoveryReconciliationService(
            ProjectionRegistry registry, UpstreamStatuses upstreamStatuses) {
        return new DiscoveryReconciliationService(registry, upstreamStatuses);
    }

    @Bean
    DiscoveryProtoFacade discoveryProtoFacade(
            DiscoveryQueryService queryService,
            DiscoverySubscriptionService subscriptionService,
            DiscoveryReconciliationService reconciliationService) {
        return new DiscoveryProtoFacade(queryService, subscriptionService, reconciliationService);
    }

    @Bean
    DiscoveryQueryGrpcService discoveryQueryGrpcService(DiscoveryProtoFacade facade) {
        return new DiscoveryQueryGrpcService(facade);
    }

    @Bean
    DiscoveryReconciliationGrpcService discoveryReconciliationGrpcService(DiscoveryProtoFacade facade) {
        return new DiscoveryReconciliationGrpcService(facade);
    }

    @Bean
    DiscoverySubscriptionGrpcService discoverySubscriptionGrpcService(
            DiscoveryProtoFacade facade, MeterRegistry meterRegistry) {
        return new DiscoverySubscriptionGrpcService(facade, meterRegistry);
    }

    @Bean
    AuthConfig authConfig(StringProperties properties) {
        return new AuthConfig(properties);
    }

    @Bean
    GrpcAuthInterceptor grpcAuthInterceptor(AuthConfig auth) {
        return new GrpcAuthInterceptor(
                auth::enabled, () -> BasicAuth.headerValueOrNull(auth.username(), auth.password()));
    }

    @Bean
    ReadinessInterceptor readinessInterceptor(DiscoveryReadinessGate readinessGate) {
        return new ReadinessInterceptor(readinessGate);
    }

    @Bean(name = "discoveryReadiness")
    HealthIndicator discoveryReadinessHealthIndicator(DiscoveryReadinessGate readinessGate) {
        return new DiscoveryReadinessHealthIndicator(readinessGate);
    }
}
