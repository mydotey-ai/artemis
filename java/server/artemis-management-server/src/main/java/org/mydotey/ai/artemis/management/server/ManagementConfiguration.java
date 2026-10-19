package org.mydotey.ai.artemis.management.server;

import java.time.Clock;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.ManagementServiceImpl;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryEjectionStore;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfig;
import org.mydotey.ai.artemis.management.server.config.ManagementConfigurationSources;
import org.mydotey.ai.artemis.management.server.grpc.RegistryClientConfig;
import org.mydotey.ai.artemis.management.server.grpc.RegistryQueryGrpcClient;
import org.mydotey.ai.artemis.management.server.http.HttpBasicAuthFilter;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 装配层（IoC）：SCF 配置、摘除存储、registry 查询客户端、应用服务与 HTTP 认证。
 */
@Configuration(proxyBeanMethods = false)
public class ManagementConfiguration {

    @Bean
    ConfigurationManager configurationManager(Environment environment) {
        return ManagementConfigurationSources.manager(environment);
    }

    /** 集中定义的只有 manager 与 properties 门面；具体 property 由各使用类在类内定义。 */
    @Bean
    StringProperties stringProperties(ConfigurationManager configurationManager) {
        return new StringProperties(configurationManager);
    }

    @Bean
    Clock clock() {
        // 摘除记录时间戳（壁挂钟，跨重启/展示用；不参与时长判定）
        return Clock.systemUTC();
    }

    @Bean
    EjectionStore ejectionStore() {
        return new InMemoryEjectionStore();
    }

    @Bean
    ManagementAuthConfig managementAuthConfig(StringProperties properties) {
        return new ManagementAuthConfig(properties);
    }

    @Bean
    RegistryClientConfig registryClientConfig(StringProperties properties) {
        return new RegistryClientConfig(properties);
    }

    @Bean(destroyMethod = "close")
    RegistryQueryGrpcClient registryQueryGrpcClient(RegistryClientConfig config) {
        return new RegistryQueryGrpcClient(config);
    }

    @Bean
    ManagementService managementService(RegistryQueryPort registryQueryPort, EjectionStore ejectionStore, Clock clock) {
        return new ManagementServiceImpl(registryQueryPort, ejectionStore, clock);
    }

    @Bean
    FilterRegistrationBean<HttpBasicAuthFilter> httpBasicAuthFilter(ManagementAuthConfig authConfig) {
        FilterRegistrationBean<HttpBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new HttpBasicAuthFilter(authConfig));
        registration.addUrlPatterns("/*");
        registration.setName("httpBasicAuthFilter");
        return registration;
    }
}
