package org.mydotey.ai.artemis.management.server.grpc;

import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriptions;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.RuleAckRegistry;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 摘除/路由规则流 gRPC 装配（条件启用，D-E11）：配置 {@code artemis.management.grpc.port}
 * 才装配规则流 service 与认证拦截器；端口映射经 {@code spring.grpc.server.port}
 * 占位符（未配置时 -1 = 不监听，management 可无 discovery 依赖独立运行）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("artemis.management.grpc.port")
public class ManagementGrpcConfiguration {

    @Bean
    ManagementGrpcAuthConfig managementGrpcAuthConfig(StringProperties properties) {
        return new ManagementGrpcAuthConfig(properties);
    }

    @Bean
    ManagementGrpcAuthInterceptor managementGrpcAuthInterceptor(ManagementGrpcAuthConfig auth) {
        return new ManagementGrpcAuthInterceptor(
                auth::enabled, () -> BasicAuth.headerValueOrNull(auth.username(), auth.password()));
    }

    @Bean
    EjectionRuleGrpcService ejectionRuleGrpcService(
            EjectionRuleSubscriptions subscriptions, @Qualifier("ejectionAckRegistry") RuleAckRegistry acks) {
        return new EjectionRuleGrpcService(subscriptions, acks);
    }

    @Bean
    RouteRuleGrpcService routeRuleGrpcService(
            RouteRuleSubscriptions subscriptions, @Qualifier("routeRuleAckRegistry") RuleAckRegistry acks) {
        return new RouteRuleGrpcService(subscriptions, acks);
    }

    @Bean
    LogicalInstanceGrpcService logicalInstanceGrpcService(LogicalInstanceSubscriptions subscriptions) {
        return new LogicalInstanceGrpcService(subscriptions);
    }
}
