package org.mydotey.ai.artemis.client.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Collections;
import java.util.Map;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.DiscoveryClient;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;

/**
 * Artemis client 注册/发现双侧自动装配：{@code artemis.client.*} 配置经 Spring {@link Environment} 进 SCF
 * （不复制 client 的键定义，也不引 {@code @ConfigurationProperties}——自定义配置的 SCF 单一来源纪律），
 * 两侧共享同一 {@link StringProperties} 与 {@link MeterRegistry} 选择逻辑。
 *
 * <p>条件语义（phase-9 D9-1）：starter 双用途，各侧按「对应 {@code addresses} 是否配置」独立装配——
 * 只做发现的消费方不必配置 registry 地址；两侧都未配时不创建任何 client，宿主正常启动。
 * 地址**已配置但非法**仍在 client 构造期 fail-fast（错误定位不变；{@code addresses} 为逗号分隔
 * 字符串键，YAML 列表写法本就不被 SCF 精确键查找支持）。{@code artemis.client.enabled=false}
 * 显式关闭两侧；宿主定义自己的 {@link RegistryClient} / {@link DiscoveryClient} 时对应侧让位（独立）。
 * 边界：装配条件只看 Spring {@link Environment}（Spring 条件机制所限）——同名接管配置桥且
 * {@code addresses} 仅存在于自定义 SCF source（不经 Spring 配置面）的宿主，请同时自定义 client bean。
 *
 * <p>配置桥让位语义（⑨ 人工验证定案）：宿主定义**同名** bean {@code artemisClientStringProperties}
 * 即接管 SCF 配置源链（SCF source 自定），client 按名注入自动跟随接管 bean；宿主定义**异名**
 * StringProperties bean（其它用途）时 starter 桥照常创建、二者共存，client 经
 * {@code @Qualifier} 按名绑定 starter 桥，不误注入宿主无关 bean。
 *
 * <p>生命周期随容器：{@code initMethod=start}；关闭经 {@code ContextClosedEvent} 监听自动 close
 * （先注册侧注销、再发现侧，均在 context close 第一时机、程序还可用时主动执行——bean 销毁阶段宿主已停流、
 * 依赖 bean 可能已销毁，尽力而为的注销失败率升高），{@code destroyMethod=close} 保留为幂等兜底（非标准关闭路径）。
 * 监听只关 starter 自建的两个 bean（按 bean 名），宿主覆盖 bean 的生命周期归宿主。
 * 发现侧只创建并启动 client，不代建订阅——选择器与订阅时机是宿主业务决定（phase-8 D-C2）。
 *
 * <p>发现侧装配独立成嵌套配置类、自带 {@code @ConditionalOnClass}：宿主钉旧版 artemis-client
 * （无 {@link DiscoveryClient}）时注册侧装配不受影响。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RegistryClient.class)
@ConditionalOnProperty(prefix = "artemis.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArtemisClientAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ArtemisClientAutoConfiguration.class);

    static final String MANAGER_NAME = "artemis-client-starter";

    /** starter 配置桥 bean 名：让位键与 client 注入限定名（同名接管 / 异名共存都不误绑）。 */
    static final String STRING_PROPERTIES_BEAN_NAME = "artemisClientStringProperties";

    static final String REGISTRY_BEAN_NAME = "artemisRegistryClient";

    static final String DISCOVERY_BEAN_NAME = "artemisDiscoveryClient";

    /**
     * Spring {@link Environment} source 是该 starter 的配置入口（application.properties/YAML 中的
     * {@code artemis.client.*}）。宿主定义同名 bean 即接管配置源链（SCF source 自定）；
     * 异名 StringProperties bean 不影响本桥创建。
     */
    @Bean(name = STRING_PROPERTIES_BEAN_NAME)
    @ConditionalOnMissingBean(name = STRING_PROPERTIES_BEAN_NAME)
    public StringProperties artemisClientStringProperties(Environment environment) {
        Map<Integer, ConfigurationSource> sources = Collections.<Integer, ConfigurationSource>singletonMap(
                1, new SpringEnvironmentConfigurationSource(environment));
        ConfigurationManager manager = ConfigurationManagers.newManager(MANAGER_NAME, sources);
        return new StringProperties(manager);
    }

    @Bean(name = REGISTRY_BEAN_NAME, initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(RegistryClient.class)
    @ConditionalOnProperty(prefix = "artemis.client.registry", name = "addresses")
    public RegistryClient artemisRegistryClient(
            @Qualifier(STRING_PROPERTIES_BEAN_NAME) StringProperties properties,
            ObjectProvider<MeterRegistry> meterRegistries) {
        // Boot/actuator 提供 MeterRegistry 时注入；缺省回落 globalRegistry（client 库模式，空转无害）
        return ArtemisClients.newRegistryClient(properties, meterRegistries.getIfUnique(() -> Metrics.globalRegistry));
    }

    /**
     * context close 第一时机自动注销（先于宿主停流与 bean 销毁）；close 幂等，destroy 阶段二次调用无害。
     * 只关 starter 自建 client（按 bean definition 的声明类判定归属）——宿主覆盖 bean 即使与 starter
     * bean 同名（类型让位后名字归宿主）也不被代关，生命周期归宿主。close 尽力而为：单个关闭失败记
     * WARN 不传播，不阻断另一侧关闭与其余 ContextClosedEvent 监听。
     */
    @Bean
    public ApplicationListener<ContextClosedEvent> artemisClientShutdownListener(
            ApplicationContext applicationContext) {
        return event -> {
            closeStarterOwned(applicationContext, REGISTRY_BEAN_NAME);
            closeStarterOwned(applicationContext, DISCOVERY_BEAN_NAME);
        };
    }

    /**
     * bean definition 声明于本自动配置（含嵌套类）才视为 starter 自建；同名宿主 bean 不在名下。
     * 声明类按 definition 的 source（反射注册为 {@link java.lang.reflect.Method}、ASM 扫描为
     * {@link MethodMetadata}）或 factoryMethodMetadata 三路取，兼容各注册路径。
     */
    private static boolean starterOwned(ApplicationContext applicationContext, String beanName) {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            return false;
        }
        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        String declaringClassName = declaringClassName(beanFactory.getBeanDefinition(beanName));
        // 精确匹配本类或其嵌套类（startsWith 会误收「本类名开头的无关类」等前缀撞名）
        return declaringClassName != null
                && (ArtemisClientAutoConfiguration.class.getName().equals(declaringClassName)
                        || declaringClassName.startsWith(ArtemisClientAutoConfiguration.class.getName() + "$"));
    }

    private static String declaringClassName(BeanDefinition definition) {
        Object source = definition.getSource();
        if (source instanceof java.lang.reflect.Method) {
            return ((java.lang.reflect.Method) source).getDeclaringClass().getName();
        }
        if (source instanceof MethodMetadata) {
            return ((MethodMetadata) source).getDeclaringClassName();
        }
        if (definition instanceof AnnotatedBeanDefinition) {
            MethodMetadata factoryMethod = ((AnnotatedBeanDefinition) definition).getFactoryMethodMetadata();
            if (factoryMethod != null) {
                return factoryMethod.getDeclaringClassName();
            }
        }
        return null;
    }

    private static void closeStarterOwned(ApplicationContext applicationContext, String beanName) {
        if (!starterOwned(applicationContext, beanName)) {
            return;
        }
        Object bean = applicationContext.getBean(beanName);
        try {
            ((AutoCloseable) bean).close();
        } catch (Exception e) {
            // 尽力而为：失败不传播（destroy 阶段幂等兜底仍在），不阻断另一侧与其余监听
            LOG.warn("failed to close {} at context close (destroy-method fallback remains)", beanName, e);
        }
    }

    /** 发现侧装配（独立 class 条件：旧版 artemis-client 无 DiscoveryClient 时本节整体跳过）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DiscoveryClient.class)
    static class DiscoveryClientConfiguration {

        @Bean(name = DISCOVERY_BEAN_NAME, initMethod = "start", destroyMethod = "close")
        @ConditionalOnMissingBean(DiscoveryClient.class)
        @ConditionalOnProperty(prefix = "artemis.client.discovery", name = "addresses")
        public DiscoveryClient artemisDiscoveryClient(
                @Qualifier(STRING_PROPERTIES_BEAN_NAME) StringProperties properties,
                ObjectProvider<MeterRegistry> meterRegistries) {
            return ArtemisClients.newDiscoveryClient(
                    properties, meterRegistries.getIfUnique(() -> Metrics.globalRegistry));
        }
    }
}
