package org.mydotey.ai.artemis.client.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Collections;
import java.util.Map;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;

/**
 * Artemis client 注册侧自动装配：{@code artemis.client.*} 配置经 Spring {@link Environment} 进 SCF
 * （不复制 client 的键定义，也不引 {@code @ConfigurationProperties}——自定义配置的 SCF 单一来源纪律），
 * 生命周期随容器：{@code initMethod=start}；关闭经 {@code ContextClosedEvent} 监听自动 close（注销在
 * context close 第一时机、程序还可用时主动执行——bean 销毁阶段宿主已停流、依赖 bean 可能已销毁，
 * 尽力而为的注销失败率升高），{@code destroyMethod=close} 保留为幂等兜底（非标准关闭路径）。
 *
 * <p>不按 {@code registry.addresses} 是否存在做条件：starter 被显式引入即表达注册意图，必配项缺失在
 * 启动期 fail-fast（client 现有语义），避免「静默不注册」。{@code artemis.client.enabled=false} 显式关闭；
 * 宿主定义自己的 {@link RegistryClient} 时自动装配让位。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RegistryClient.class)
@ConditionalOnProperty(prefix = "artemis.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArtemisClientAutoConfiguration {

    static final String MANAGER_NAME = "artemis-client-starter";

    /**
     * 不设 {@code @ConditionalOnMissingBean}：Spring {@link Environment} source 是该 starter 的配置
     * 入口，复用宿主无关的 SCF bean 会静默丢失 application.properties/YAML 中的 {@code artemis.client.*}。
     * 宿主定制配置源 / 客户端实现的出口是自定义 {@link RegistryClient} bean（自动装配整体让位）。
     */
    @Bean
    public StringProperties artemisClientStringProperties(Environment environment) {
        Map<Integer, ConfigurationSource> sources = Collections.<Integer, ConfigurationSource>singletonMap(
                1, new SpringEnvironmentConfigurationSource(environment));
        ConfigurationManager manager = ConfigurationManagers.newManager(MANAGER_NAME, sources);
        return new StringProperties(manager);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public RegistryClient artemisClient(StringProperties properties, ObjectProvider<MeterRegistry> meterRegistries) {
        // Boot/actuator 提供 MeterRegistry 时注入；缺省回落 globalRegistry（client 库模式，空转无害）
        return ArtemisClients.newRegistryClient(properties, meterRegistries.getIfUnique(() -> Metrics.globalRegistry));
    }

    /** context close 第一时机自动注销（先于宿主停流与 bean 销毁）；close 幂等，destroy 阶段二次调用无害。 */
    @Bean
    public ApplicationListener<ContextClosedEvent> artemisClientShutdownListener(
            ObjectProvider<RegistryClient> artemisClients) {
        return event -> artemisClients.ifAvailable(RegistryClient::close);
    }
}
