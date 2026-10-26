package org.mydotey.ai.artemis.management.server;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.application.DbAccountAuthService;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.ManagementServiceImpl;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.EjectionAckRegistry;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceStore;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.infrastructure.EjectionRuleDispatcher;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryAuditLogStore;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryEjectionStore;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryLogicalInstanceStore;
import org.mydotey.ai.artemis.management.infrastructure.LogicalInstanceDispatcher;
import org.mydotey.ai.artemis.management.server.auth.ConfigAccountAuthService;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfig;
import org.mydotey.ai.artemis.management.server.config.ManagementConfigurationSources;
import org.mydotey.ai.artemis.management.server.grpc.RegistryClientConfig;
import org.mydotey.ai.artemis.management.server.grpc.RegistryQueryGrpcClient;
import org.mydotey.ai.artemis.management.server.http.HttpBasicAuthFilter;
import org.mydotey.ai.artemis.management.server.repository.AccountMapper;
import org.mydotey.ai.artemis.management.server.repository.AccountStoreImpl;
import org.mydotey.ai.artemis.management.server.repository.AuditLogMapper;
import org.mydotey.ai.artemis.management.server.repository.AuditLogStoreImpl;
import org.mydotey.ai.artemis.management.server.repository.EjectionRecordMapper;
import org.mydotey.ai.artemis.management.server.repository.EjectionStoreImpl;
import org.mydotey.ai.artemis.management.server.repository.LogicalInstanceMapper;
import org.mydotey.ai.artemis.management.server.repository.LogicalInstanceStoreImpl;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 装配层（IoC）：SCF 配置、摘除存储与规则分发、registry 查询客户端、应用服务与认证裁决。
 * 认证双轨（ADR-021）：DB 态验账户表（seed 一次性物化配置凭证），内存态比对配置凭证。
 * 规则流 gRPC 装配条件启用（{@code ManagementGrpcConfiguration}）。
 */
@Slf4j
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

    /**
     * 摘除存储双轨（判据 = mapper 可得性，随 DataSource bean 存在性，phase-2 模式）：
     * DB 态 MyBatis 实现（自增主键 = sequence，跨重启持久），内存态期 1 行为。
     */
    @Bean
    EjectionStore ejectionStore(ObjectProvider<EjectionRecordMapper> mapperProvider) {
        EjectionRecordMapper mapper = mapperProvider.getIfAvailable();
        return mapper == null ? new InMemoryEjectionStore() : new EjectionStoreImpl(mapper);
    }

    /** 逻辑实例存储双轨（同判据；时间戳写入在 DB 实现，内存态无时间戳语义）。 */
    @Bean
    LogicalInstanceStore logicalInstanceStore(ObjectProvider<LogicalInstanceMapper> mapperProvider, Clock clock) {
        LogicalInstanceMapper mapper = mapperProvider.getIfAvailable();
        return mapper == null ? new InMemoryLogicalInstanceStore() : new LogicalInstanceStoreImpl(mapper, clock);
    }

    /** 审计存储双轨（同判据；内存态重启丢失为既有语义）。 */
    @Bean
    AuditLogStore auditLogStore(ObjectProvider<AuditLogMapper> mapperProvider) {
        AuditLogMapper mapper = mapperProvider.getIfAvailable();
        return mapper == null ? new InMemoryAuditLogStore() : new AuditLogStoreImpl(mapper);
    }

    /** 规则分发无条件装配（无 gRPC 端口时零订阅者，摘除/恢复通知为空操作）。 */
    @Bean(destroyMethod = "close")
    EjectionRuleDispatcher ejectionRuleDispatcher(EjectionStore ejectionStore) {
        return new EjectionRuleDispatcher(ejectionStore);
    }

    /** 生效 ack 登记无条件装配（phase-5；内存观测态，无订阅者零条目）。 */
    @Bean
    EjectionAckRegistry ejectionAckRegistry(Clock clock) {
        return new EjectionAckRegistry(clock);
    }

    /** 逻辑实例流分发无条件装配（无 gRPC 端口时零订阅者，通知为空操作）。 */
    @Bean(destroyMethod = "close")
    LogicalInstanceDispatcher logicalInstanceDispatcher(LogicalInstanceStore logicalInstanceStore) {
        return new LogicalInstanceDispatcher(logicalInstanceStore);
    }

    @Bean
    ManagementAuthConfig managementAuthConfig(StringProperties properties) {
        return new ManagementAuthConfig(properties);
    }

    /**
     * 账户存储（DB 态）：条件与 DataSource 同键（phase-1 双轨判据），保证内存态零注册——
     * MyBatis-Plus autoconfig 亦因无 DataSource bean 不激活，@Mapper 零实例化。
     */
    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    AccountStore accountStore(AccountMapper accountMapper) {
        return new AccountStoreImpl(accountMapper);
    }

    /**
     * 认证裁决二选一（ADR-021）：DB 态 seed 在 migration 后执行——本 bean 显式依赖
     * {@code persistenceInfo}（其构造触发 Flyway），保证建表先于 seed。
     */
    @Bean
    AccountAuthService accountAuthService(
            ObjectProvider<AccountStore> accountStoreProvider,
            ManagementAuthConfig authConfig,
            PersistenceInfo persistenceInfo,
            Clock clock) {
        AccountStore store = accountStoreProvider.getIfAvailable();
        if (store == null) {
            return new ConfigAccountAuthService(authConfig);
        }
        DbAccountAuthService service = new DbAccountAuthService(store, clock);
        service.seedIfAbsent(authConfig.username(), authConfig.password());
        if (authConfig.usingDefaultPassword() && service.passwordInUse(ManagementAuthConfig.DEFAULT_PASSWORD)) {
            log.warn("management account still uses the default password, "
                    + "change it via PUT /admin/v1/account/password");
        }
        return service;
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
    ManagementService managementService(
            RegistryQueryPort registryQueryPort,
            EjectionStore ejectionStore,
            AuditLogStore auditLogStore,
            EjectionRuleDispatcher ejectionRuleDispatcher,
            EjectionAckRegistry ejectionAckRegistry,
            LogicalInstanceStore logicalInstanceStore,
            LogicalInstanceDispatcher logicalInstanceDispatcher,
            Clock clock) {
        return new ManagementServiceImpl(
                registryQueryPort,
                ejectionStore,
                auditLogStore,
                ejectionRuleDispatcher,
                ejectionAckRegistry,
                logicalInstanceStore,
                logicalInstanceDispatcher,
                clock);
    }

    @Bean
    FilterRegistrationBean<HttpBasicAuthFilter> httpBasicAuthFilter(
            ManagementAuthConfig authConfig, AccountAuthService accountAuthService) {
        FilterRegistrationBean<HttpBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new HttpBasicAuthFilter(authConfig, accountAuthService));
        registration.addUrlPatterns("/*");
        registration.setName("httpBasicAuthFilter");
        return registration;
    }
}
