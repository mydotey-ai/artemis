package org.mydotey.ai.artemis.discovery.server;

import io.micrometer.core.instrument.MeterRegistry;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.discovery.application.DiscoveryAdminService;
import org.mydotey.ai.artemis.discovery.application.DiscoveryProtoFacade;
import org.mydotey.ai.artemis.discovery.application.DiscoveryQueryService;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.mydotey.ai.artemis.discovery.application.DiscoveryReconciliationService;
import org.mydotey.ai.artemis.discovery.application.DiscoverySubscriptionService;
import org.mydotey.ai.artemis.discovery.domain.DiscoveryFilterChain;
import org.mydotey.ai.artemis.discovery.domain.EjectionFilter;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatuses;
import org.mydotey.ai.artemis.discovery.infrastructure.EjectionRuleSynchronizer;
import org.mydotey.ai.artemis.discovery.server.auth.AuthConfig;
import org.mydotey.ai.artemis.discovery.server.config.DiscoveryConfigurationSources;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoveryQueryGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoveryReconciliationGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.DiscoverySubscriptionGrpcService;
import org.mydotey.ai.artemis.discovery.server.grpc.GrpcAuthInterceptor;
import org.mydotey.ai.artemis.discovery.server.grpc.ReadinessInterceptor;
import org.mydotey.ai.artemis.discovery.server.grpc.RemoteAddressInterceptor;
import org.mydotey.ai.artemis.discovery.server.health.DiscoveryReadinessHealthIndicator;
import org.mydotey.ai.artemis.discovery.server.http.HttpBasicAuthFilter;
import org.mydotey.ai.artemis.discovery.server.upstream.GrpcEjectionRuleSource;
import org.mydotey.ai.artemis.discovery.server.upstream.ManagementAuthConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.ManagementConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.NodeDirectoryConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamAuthConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamSynchronizers;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /** 就绪门为全量门（phase-5）：全部配置上游首轮 SYNCED 才 ready，断连不降（D-D5）。 */
    @Bean
    DiscoveryReadinessGate discoveryReadinessGate(UpstreamConfig upstreamConfig) {
        return new DiscoveryReadinessGate(upstreamConfig.upstreams().size());
    }

    @Bean
    ProjectionRegistry projectionRegistry() {
        return new ProjectionRegistry();
    }

    /** 过滤器链（phase-6，D-E4）：未配置 management = 空链直通（零过滤零开销）。 */
    @Bean
    ManagementConfig managementConfig(StringProperties properties) {
        return new ManagementConfig(properties);
    }

    @Bean
    ManagementAuthConfig managementAuthConfig(StringProperties properties) {
        return new ManagementAuthConfig(properties);
    }

    @Bean
    EjectionFilter ejectionFilter() {
        return new EjectionFilter();
    }

    @Bean
    DiscoveryFilterChain discoveryFilterChain(ManagementConfig managementConfig, EjectionFilter ejectionFilter) {
        return managementConfig.enabled() ? DiscoveryFilterChain.of(ejectionFilter) : DiscoveryFilterChain.empty();
    }

    @Bean
    SubscriptionManager subscriptionManager(ProjectionRegistry registry, DiscoveryFilterChain chain) {
        return new SubscriptionManager(registry, chain);
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

    /** 多上游同步编排（phase-5）：per cluster (source, synchronizer)，共享投影分表与扇出表锁；
     * 订阅扇出内联同步线程（D-S4/D-S7）；状态转移上报新鲜度表（D-F5）。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    UpstreamSynchronizers upstreamSynchronizers(
            StringProperties properties,
            UpstreamConfig upstreamConfig,
            UpstreamAuthConfig upstreamAuthConfig,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            DiscoveryReadinessGate readinessGate,
            UpstreamStatuses upstreamStatuses) {
        return new UpstreamSynchronizers(
                properties,
                upstreamConfig,
                upstreamAuthConfig,
                registry,
                subscriptionManager,
                readinessGate,
                upstreamStatuses);
    }

    /** 规则流 gRPC 源（channel 池随 context 关闭释放；未启用时惰性零连接）。 */
    @Bean(destroyMethod = "close")
    GrpcEjectionRuleSource grpcEjectionRuleSource(
            StringProperties properties, ManagementConfig managementConfig, ManagementAuthConfig managementAuthConfig) {
        return new GrpcEjectionRuleSource(properties, managementConfig, managementAuthConfig);
    }

    /** 规则源状态持有者（phase-7 D-A5）：同步引擎上报、管理面查询。 */
    @Bean
    ManagementStatus managementStatus() {
        return new ManagementStatus();
    }

    /** 规则同步引擎（phase-6）：未配置 management 时不启动线程（0..1，D-E4/D-E3）。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    EjectionRuleSynchronizer ejectionRuleSynchronizer(
            StringProperties properties,
            ManagementConfig managementConfig,
            GrpcEjectionRuleSource ejectionRuleSource,
            EjectionFilter ejectionFilter,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            ManagementStatus managementStatus) {
        EjectionRuleSynchronizer synchronizer = new EjectionRuleSynchronizer(
                properties, ejectionRuleSource, ejectionFilter, registry, subscriptionManager, managementStatus);
        if (!managementConfig.enabled()) {
            synchronizer.disable(); // 0..1：不建连接不启线程
        }
        return synchronizer;
    }

    @Bean
    DiscoveryQueryService discoveryQueryService(
            ProjectionRegistry registry, DiscoveryFilterChain chain, UpstreamStatuses upstreamStatuses) {
        return new DiscoveryQueryService(registry, chain, upstreamStatuses);
    }

    @Bean
    DiscoverySubscriptionService discoverySubscriptionService(SubscriptionManager manager) {
        return new DiscoverySubscriptionService(manager);
    }

    @Bean
    DiscoveryReconciliationService discoveryReconciliationService(
            ProjectionRegistry registry, DiscoveryFilterChain chain, UpstreamStatuses upstreamStatuses) {
        return new DiscoveryReconciliationService(registry, chain, upstreamStatuses);
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

    /** 发现管理面只读用例（phase-7）：服务视图经同一过滤链（有效视图，D-A6）。 */
    @Bean
    DiscoveryAdminService discoveryAdminService(
            SubscriptionManager subscriptionManager,
            ProjectionRegistry registry,
            DiscoveryFilterChain chain,
            UpstreamStatuses upstreamStatuses,
            ManagementStatus managementStatus) {
        return new DiscoveryAdminService(subscriptionManager, registry, chain, upstreamStatuses, managementStatus);
    }

    /** 管理面 HTTP 认证：仅 /admin/*（D-A7），账户与外部 gRPC 共用。 */
    @Bean
    FilterRegistrationBean<HttpBasicAuthFilter> discoveryAdminAuthFilter(AuthConfig auth) {
        FilterRegistrationBean<HttpBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new HttpBasicAuthFilter(auth));
        registration.addUrlPatterns("/admin/*", "/cluster/nodes");
        registration.setName("discoveryAdminAuthFilter");
        return registration;
    }

    /** 静态节点目录（ADR-020，SDK 前置寻址）：未配置 = 空目录。 */
    @Bean
    NodeDirectoryConfig nodeDirectoryConfig(StringProperties properties) {
        return new NodeDirectoryConfig(properties);
    }

    /** 调用方地址捕获（D-A2）：订阅关系登记在管理面可见。 */
    @Bean
    RemoteAddressInterceptor remoteAddressInterceptor() {
        return new RemoteAddressInterceptor();
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
