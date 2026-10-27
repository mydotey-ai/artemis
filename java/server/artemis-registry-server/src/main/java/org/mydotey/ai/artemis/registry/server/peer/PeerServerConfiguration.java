package org.mydotey.ai.artemis.registry.server.peer;

import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.tls.GrpcTlsContexts;
import org.mydotey.ai.artemis.common.tls.TlsClientSettings;
import org.mydotey.ai.artemis.common.tls.TlsServerSettings;
import org.mydotey.ai.artemis.common.tls.TlsSettings;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.server.auth.PeerAuthConfig;
import org.mydotey.ai.artemis.registry.server.grpc.BasicAuthServerInterceptor;
import org.mydotey.ai.artemis.registry.server.grpc.ProjectionGrpcService;
import org.mydotey.ai.artemis.registry.server.grpc.ReadinessInterceptor;
import org.mydotey.ai.artemis.registry.server.grpc.RegistryQueryGrpcService;
import org.mydotey.ai.artemis.registry.server.grpc.ReplicationGrpcService;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.DefaultGrpcServerFactory;
import org.springframework.grpc.server.NettyGrpcServerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.grpc.server.ServerServiceDefinitionFilter;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 内部 peer 端口装配：第二 gRPC 监听（官方 {@link NettyGrpcServerFactory}
 * + {@link GrpcServerLifecycle}，不手写 io.grpc.Server 生命周期）。
 *
 * <p>端口隔离：Boot 的 {@code DefaultGrpcServiceDiscoverer} 会收集**全部** {@code BindableService}
 * bean（无论是否标注 {@code @GrpcService}），因此内部服务通过 {@link GrpcServerFactoryCustomizer}
 * 的 service filter 从主 server 排除；内部服务只在本 listener 显式绑定。
 *
 * <p>拦截器：内部端口不使用主 server 的 {@code @GlobalServerInterceptor}（外部认证/readiness
 * 会泄漏），而是按 service 显式绑定内部认证 + 框架观测/异常映射 + 查询的 readiness 门控。
 * gRPC 拦截器列表**后者最外层**：auth 放在末尾，保证认证先于 readiness 判定。
 */
@Configuration(proxyBeanMethods = false)
public class PeerServerConfiguration {

    /** 只允许出现在内部端口的服务（主 server 装配时过滤）。 */
    private static final Set<String> INTERNAL_SERVICES = Set.of(
            "artemis.replication.v1.ReplicationService",
            "artemis.projection.v1.ProjectionService",
            "artemis.management.v1.RegistryQueryService");

    /**
     * 主 server 的 service 过滤器：显式排除内部服务（见类注释）。
     * customizer 由主 server 自动装配消费；内部 factory 为手工构造，不受影响。
     */
    @Bean
    GrpcServerFactoryCustomizer peerServicesExcludedFromMainServer() {
        ServerServiceDefinitionFilter filter = (definition, factory) ->
                !INTERNAL_SERVICES.contains(definition.getServiceDescriptor().getName());
        return factory -> {
            if (factory instanceof DefaultGrpcServerFactory<?> defaultFactory) {
                defaultFactory.setServiceFilter(filter);
            }
        };
    }

    @Bean
    PeerServerConfig peerServerConfig(StringProperties properties) {
        return new PeerServerConfig(properties);
    }

    @Bean
    PeerAuthConfig peerAuthConfig(StringProperties properties) {
        return new PeerAuthConfig(properties);
    }

    /** peer 端口 TLS（静态）：入站证书 + 出站信任同组键 {@code artemis.registry.internal.tls}。 */
    @Bean
    TlsServerSettings peerTlsServerSettings(StringProperties properties) {
        return TlsSettings.server(properties, "artemis.registry.internal.tls");
    }

    @Bean
    TlsClientSettings peerTlsClientSettings(StringProperties properties) {
        return TlsSettings.client(properties, "artemis.registry.internal.tls");
    }

    /** per-peer channel 池：复制投递与 bootstrap 探测/拉取共用，关闭随 context 释放。 */
    @Bean(destroyMethod = "close")
    PeerChannels peerChannels(
            StringProperties properties, PeerAuthConfig peerAuthConfig, TlsClientSettings peerTlsClientSettings) {
        return new PeerChannels(properties, peerAuthConfig, peerTlsClientSettings);
    }

    /** 出站 peer 复制传输（领域端口实现）。 */
    @Bean
    GrpcPeerReplicator grpcPeerReplicator(StringProperties properties, PeerChannels peerChannels) {
        return new GrpcPeerReplicator(properties, peerChannels);
    }

    /** 出站启动数据源（状态查询 + 全量 bootstrap，领域端口实现）。 */
    @Bean
    GrpcPeerBootstrapSource grpcPeerBootstrapSource(
            StringProperties properties, PeerChannels peerChannels, ClusterMembers clusterMembers) {
        return new GrpcPeerBootstrapSource(properties, peerChannels, clusterMembers);
    }

    /** 命名虚拟线程 executor（gRPC handler 规范，guidelines §4.2）；关闭时中断任务，不无限等待。 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService peerGrpcExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("artemis-peer-grpc-", 0).factory());
    }

    @Bean
    GrpcServerLifecycle peerGrpcServerLifecycle(
            PeerServerConfig config,
            PeerAuthConfig auth,
            TlsServerSettings tls,
            @Qualifier("peerGrpcExecutor") ExecutorService executor,
            ReplicationGrpcService replicationService,
            ProjectionGrpcService projectionService,
            RegistryQueryGrpcService queryService,
            ReadinessInterceptor readinessInterceptor,
            ObjectProvider<ObservationGrpcServerInterceptor> observationInterceptor,
            ObjectProvider<GrpcExceptionHandlerInterceptor> exceptionHandlerInterceptor,
            ApplicationEventPublisher publisher) {
        List<ServerBuilderCustomizer<NettyServerBuilder>> customizers =
                List.of(builder -> builder.executor(executor).maxInboundMessageSize(config.maxInboundMessageBytes()));
        // TLS（artemis.registry.internal.tls，静态）：经 factory 构造参数接入——factory 内部以
        // ServerCredentials 构 builder，安全设置被冻结，customizer 挂 sslContext 会抛
        // "Cannot change security"（GrpcTlsContexts 类注释）；默认明文（架构 §6 内网可配明文）
        NettyGrpcServerFactory factory = new NettyGrpcServerFactory(
                config.bindAddress(),
                customizers,
                tls.enabled() ? GrpcTlsContexts.serverKeyManagers(tls) : null,
                null,
                null);

        BasicAuthServerInterceptor internalAuth = new BasicAuthServerInterceptor(
                auth::enabled, () -> BasicAuth.headerValueOrNull(auth.username(), auth.password()), Set.of());

        List<ServerInterceptor> queryInterceptors = new ArrayList<>();
        queryInterceptors.add(readinessInterceptor);
        exceptionHandlerInterceptor.ifAvailable(queryInterceptors::add);
        observationInterceptor.ifAvailable(queryInterceptors::add);
        queryInterceptors.add(internalAuth);

        List<ServerInterceptor> replicationInterceptors = new ArrayList<>();
        exceptionHandlerInterceptor.ifAvailable(replicationInterceptors::add);
        observationInterceptor.ifAvailable(replicationInterceptors::add);
        replicationInterceptors.add(internalAuth);

        // 投影分发：readiness 在 service 方法内门控（同复制），认证先于一切
        List<ServerInterceptor> projectionInterceptors = new ArrayList<>();
        exceptionHandlerInterceptor.ifAvailable(projectionInterceptors::add);
        observationInterceptor.ifAvailable(projectionInterceptors::add);
        projectionInterceptors.add(internalAuth);

        factory.addService(ServerInterceptors.intercept(replicationService, replicationInterceptors));
        factory.addService(ServerInterceptors.intercept(projectionService, projectionInterceptors));
        factory.addService(ServerInterceptors.intercept(queryService, queryInterceptors));
        return new GrpcServerLifecycle(factory, Duration.ofMillis(config.shutdownGraceMs()), publisher);
    }
}
