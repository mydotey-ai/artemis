package org.mydotey.ai.artemis.client.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.DiscoveryClient;
import org.mydotey.ai.artemis.client.DiscoveryListener;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.Subscription;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StreamUtils;

class ArtemisClientAutoConfigurationTest {

    /** 不建连的本地地址：client 启动只挂后台任务，网络交互全部失败不影响断言。 */
    private static final String REGISTRY_ADDRESS = "artemis.client.registry.addresses=http://127.0.0.1:1";

    private static final String DISCOVERY_ADDRESS = "artemis.client.discovery.addresses=http://127.0.0.1:1";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ArtemisClientAutoConfiguration.class);

    @Test
    void should_create_registry_client_when_registry_addresses_configured() {
        AtomicReference<RegistryClient> client = new AtomicReference<>();
        runner.withPropertyValues(REGISTRY_ADDRESS).run(context -> {
            assertThat(context).hasSingleBean(RegistryClient.class);
            assertThat(context).doesNotHaveBean(DiscoveryClient.class);
            client.set(context.getBean(RegistryClient.class));
            // 已由 initMethod 启动：再次 start 报 already started
            assertThatThrownBy(client.get()::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already started");
        });
        // 容器关闭已触发 destroyMethod=close（非标准关闭路径的兜底）
        assertThatThrownBy(() -> client.get().register(instance()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void should_not_create_registry_client_when_registry_addresses_missing() {
        // 缺 registry 地址不再 fail-fast（D9-1 双用途）：只做发现的消费方不被迫配 registry
        runner.withPropertyValues(DISCOVERY_ADDRESS)
                .run(context -> assertThat(context).doesNotHaveBean(RegistryClient.class));
    }

    @Test
    void should_create_discovery_client_when_discovery_addresses_configured() {
        runner.withPropertyValues(DISCOVERY_ADDRESS).run(context -> {
            assertThat(context).hasSingleBean(DiscoveryClient.class);
            assertThat(context).doesNotHaveBean(RegistryClient.class);
            assertThatThrownBy(context.getBean(DiscoveryClient.class)::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already started");
        });
    }

    @Test
    void should_create_both_clients_when_both_addresses_configured() {
        runner.withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS).run(context -> {
            assertThat(context).hasSingleBean(RegistryClient.class);
            assertThat(context).hasSingleBean(DiscoveryClient.class);
        });
    }

    @Test
    void should_not_create_any_client_when_no_addresses_configured() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RegistryClient.class);
            assertThat(context).doesNotHaveBean(DiscoveryClient.class);
        });
    }

    @Test
    void should_not_create_clients_when_enabled_false() {
        runner.withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS, "artemis.client.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RegistryClient.class);
                    assertThat(context).doesNotHaveBean(DiscoveryClient.class);
                });
    }

    @Test
    void should_fail_fast_when_configured_address_invalid() {
        // 配了非法地址仍构造期 fail-fast（两侧同语义：SCF filter 拒收后构造校验兜底）
        runner.withPropertyValues("artemis.client.registry.addresses=not-a-url")
                .run(
                        context -> assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .hasRootCauseMessage(
                                        "artemis.client.registry.addresses must be set to valid http(s) url(s), e.g. http://artemis.example.com (missing or invalid — check WARN log for rejected values)"));
        runner.withPropertyValues("artemis.client.discovery.addresses=not-a-url")
                .run(
                        context -> assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .hasRootCauseMessage(
                                        "artemis.client.discovery.addresses must be set to valid http(s) url(s), e.g. http://artemis.example.com (missing or invalid — check WARN log for rejected values)"));
    }

    @Test
    void should_inject_boot_meter_registry_into_discovery_client() {
        // 独立 clientId：断言限定本用例的指标序列，不受其它用例落入 globalRegistry 的同名 meter 干扰
        String clientId = "meter-inject-test";
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withPropertyValues(DISCOVERY_ADDRESS, "artemis.client.clientId=" + clientId)
                .withBean(SimpleMeterRegistry.class, () -> registry)
                .run(context -> {
                    // 发现侧前置寻址指标（构造期注册）写入宿主 registry
                    assertThat(registry.find("artemis.client.nodelist.refresh")
                                    .tag("client", clientId)
                                    .counter())
                            .isNotNull();
                    // 注入宿主 registry 时不得同时写 global（防重复指标序列）
                    assertThat(Metrics.globalRegistry
                                    .find("artemis.client.nodelist.refresh")
                                    .tag("client", clientId)
                                    .counter())
                            .isNull();
                });
    }

    @Test
    void should_inject_boot_meter_registry_into_registry_client() {
        String clientId = "registry-meter-inject-test";
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withPropertyValues(REGISTRY_ADDRESS, "artemis.client.clientId=" + clientId)
                .withBean(SimpleMeterRegistry.class, () -> registry)
                .run(context -> {
                    // 注册侧本地集 gauge（构造期注册）写入宿主 registry
                    assertThat(registry.find("artemis.client.instances")
                                    .tag("client", clientId)
                                    .gauge())
                            .isNotNull();
                    assertThat(Metrics.globalRegistry
                                    .find("artemis.client.instances")
                                    .tag("client", clientId)
                                    .gauge())
                            .isNull();
                });
    }

    @Test
    void should_fallback_to_global_registry_when_no_meter_registry() {
        String clientId = "global-fallback-test";
        runner.withPropertyValues(DISCOVERY_ADDRESS, "artemis.client.clientId=" + clientId)
                .run(context -> assertThat(Metrics.globalRegistry
                                .find("artemis.client.nodelist.refresh")
                                .tag("client", clientId)
                                .counter())
                        .isNotNull());
    }

    @Test
    void should_close_both_clients_when_context_closed() {
        runner.withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS).run(context -> {
            // context close 第一时机（bean 销毁前）手动发布事件：注销在程序还可用时主动执行
            context.publishEvent(new ContextClosedEvent(context));

            assertThatThrownBy(() -> context.getBean(RegistryClient.class).register(instance()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(() -> context.getBean(DiscoveryClient.class).subscribe(selector()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        });
        // destroy 阶段二次 close 幂等 no-op，容器正常关闭
    }

    @Test
    void should_allow_host_override_for_each_client() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostClientsConfiguration.class, ArtemisClientAutoConfiguration.class)
                .withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS)
                .run(context -> {
                    assertThat(context.getBean(RegistryClient.class)).isSameAs(HostClientsConfiguration.REGISTRY);
                    assertThat(context.getBean(DiscoveryClient.class)).isSameAs(HostClientsConfiguration.DISCOVERY);
                });
    }

    @Test
    void should_allow_host_takeover_of_string_properties_bean() {
        // 宿主定义同名 bean 即接管配置桥：starter 桥让位（容器仅一个 StringProperties = 宿主 bean），
        // client 按名注入跟随接管 bean（缺 bean 即启动失败）
        new ApplicationContextRunner()
                .withUserConfiguration(HostTakeoverConfiguration.class, ArtemisClientAutoConfiguration.class)
                .withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS)
                .run(context -> {
                    assertThat(context.getBeansOfType(StringProperties.class)).hasSize(1);
                    assertThat(context).hasSingleBean(RegistryClient.class);
                    assertThat(context).hasSingleBean(DiscoveryClient.class);
                });
    }

    @Test
    void should_bind_clients_to_starter_bridge_when_other_properties_bean_present() {
        // 宿主异名 StringProperties bean（无 artemis 配置）共存：client 按名绑 starter 桥，
        // 误注入宿主 bean 会因缺 addresses 构造期 fail-fast——上下文正常启动即证明绑定正确
        new ApplicationContextRunner()
                .withUserConfiguration(OtherPropertiesConfiguration.class, ArtemisClientAutoConfiguration.class)
                .withPropertyValues(REGISTRY_ADDRESS, DISCOVERY_ADDRESS)
                .run(context -> {
                    assertThat(context.getBeansOfType(StringProperties.class)).hasSize(2);
                    assertThat(context).hasSingleBean(RegistryClient.class);
                    assertThat(context).hasSingleBean(DiscoveryClient.class);
                });
    }

    @Test
    void should_not_close_host_bean_with_same_bean_name() {
        // 宿主 bean 与 starter bean 同名（类型让位后名字归宿主）：starter 关停监听不得代关宿主 bean
        new ApplicationContextRunner()
                .withUserConfiguration(SameNameClientConfiguration.class, ArtemisClientAutoConfiguration.class)
                .withPropertyValues(REGISTRY_ADDRESS)
                .run(context -> {
                    assertThat(context.getBean(RegistryClient.class)).isSameAs(SameNameClientConfiguration.CLIENT);
                    context.publishEvent(new ContextClosedEvent(context));
                    assertThat(SameNameClientConfiguration.CLIENT.closed)
                            .as("host bean with same name must not be closed by starter listener")
                            .isFalse();
                });
    }

    @Test
    void should_register_auto_configuration_in_both_boot_registries() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        Properties factories = new Properties();
        try (InputStream in = classLoader.getResourceAsStream("META-INF/spring.factories")) {
            assertThat(in).isNotNull();
            factories.load(in);
        }
        assertThat(factories.getProperty("org.springframework.boot.autoconfigure.EnableAutoConfiguration"))
                .contains(ArtemisClientAutoConfiguration.class.getName());

        String imports = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = classLoader.getResourceAsStream(imports)) {
            assertThat(in).isNotNull();
            assertThat(StreamUtils.copyToString(in, StandardCharsets.UTF_8))
                    .contains(ArtemisClientAutoConfiguration.class.getName());
        }
    }

    @Test
    void should_resolve_relaxed_key_variants() {
        // camel 段键的 kebab/snake 变体 + 点分层键的大写蛇形环境变量变体
        assertThat(sourceValue("artemis.client.heartbeat.interval-ms", "kebab", "artemis.client.heartbeat.intervalMs"))
                .isEqualTo("kebab");
        assertThat(sourceValue("ARTEMIS_CLIENT_HEARTBEAT_INTERVAL_MS", "snake", "artemis.client.heartbeat.intervalMs"))
                .isEqualTo("snake");
        assertThat(sourceValue("ARTEMIS_CLIENT_REGISTRY_ADDRESSES", "env", "artemis.client.registry.addresses"))
                .isEqualTo("env");
    }

    private static Object sourceValue(String variant, String value, String canonicalKey) {
        Map<String, Object> values = Collections.<String, Object>singletonMap(variant, value);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        // 同包可见：直接验证 source 的键名归一化
        return new SpringEnvironmentConfigurationSource(environment).getPropertyValue(canonicalKey);
    }

    private static DiscoverySelector selector() {
        return DiscoverySelector.newBuilder()
                .addService(
                        ServiceId.newBuilder().namespace("ns").service("svc").build())
                .build();
    }

    private static ServiceInstance instance() {
        return ServiceInstance.newBuilder()
                .service(ServiceId.newBuilder().namespace("ns").service("svc").build())
                .instanceId("i-1")
                .host("127.0.0.1")
                .port(8080)
                .build();
    }

    private static final class StubRegistryClient implements RegistryClient {

        @Override
        public void start() {}

        @Override
        public void register(ServiceInstance instance) {}

        @Override
        public void update(ServiceInstance instance) {}

        @Override
        public void deregister(InstanceId instance) {}

        @Override
        public void close() {}
    }

    private static final class StubDiscoveryClient implements DiscoveryClient {

        @Override
        public void start() {}

        @Override
        public Subscription subscribe(DiscoverySelector selector, DiscoveryListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    @Configuration(proxyBeanMethods = false)
    static class HostClientsConfiguration {

        static final StubRegistryClient REGISTRY = new StubRegistryClient();

        static final StubDiscoveryClient DISCOVERY = new StubDiscoveryClient();

        @Bean
        RegistryClient customRegistryClient() {
            return REGISTRY;
        }

        @Bean
        DiscoveryClient customDiscoveryClient() {
            return DISCOVERY;
        }
    }

    /** 同名接管：自定义 SCF source 链（仍读 Spring Environment，模拟宿主定制 source）。 */
    @Configuration(proxyBeanMethods = false)
    static class HostTakeoverConfiguration {

        @Bean(name = "artemisClientStringProperties")
        StringProperties artemisClientStringProperties(Environment environment) {
            return new StringProperties(ConfigurationManagers.newManager(
                    "host-takeover-env",
                    Collections.<Integer, ConfigurationSource>singletonMap(
                            1, new SpringEnvironmentConfigurationSource(environment))));
        }
    }

    /** 异名共存：与 artemis 无关的 StringProperties bean（裸 StandardEnvironment source，无 artemis 键）。 */
    @Configuration(proxyBeanMethods = false)
    static class OtherPropertiesConfiguration {

        @Bean
        StringProperties otherStringProperties() {
            return new StringProperties(ConfigurationManagers.newManager(
                    "other-props-test",
                    Collections.<Integer, ConfigurationSource>singletonMap(
                            1, new SpringEnvironmentConfigurationSource(new StandardEnvironment()))));
        }
    }

    /** 同名撞车：宿主 client bean 注册为 starter 的 bean 名（类型让位、名字归宿主）。 */
    @Configuration(proxyBeanMethods = false)
    static class SameNameClientConfiguration {

        static final RecordingRegistryClient CLIENT = new RecordingRegistryClient();

        @Bean(name = "artemisRegistryClient")
        RegistryClient artemisRegistryClient() {
            return CLIENT;
        }
    }

    static final class RecordingRegistryClient implements RegistryClient {

        volatile boolean closed;

        @Override
        public void start() {}

        @Override
        public void register(ServiceInstance instance) {}

        @Override
        public void update(ServiceInstance instance) {}

        @Override
        public void deregister(InstanceId instance) {}

        @Override
        public void close() {
            closed = true;
        }
    }
}
