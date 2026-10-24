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
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StreamUtils;

class ArtemisClientAutoConfigurationTest {

    private static final String ADDRESS = "artemis.client.registry.addresses=http://127.0.0.1:1";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ArtemisClientAutoConfiguration.class);

    @Test
    void should_create_started_client_when_addresses_configured() {
        AtomicReference<RegistryClient> client = new AtomicReference<>();
        runner.withPropertyValues(ADDRESS).run(context -> {
            RegistryClient bean = context.getBean(RegistryClient.class);
            client.set(bean);
            // 已由 initMethod 启动：再次 start 报 already started
            assertThatThrownBy(bean::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already started");
        });
        // 容器关闭已触发 destroyMethod=close
        assertThatThrownBy(() -> client.get().register(instance()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void should_closeClient_when_contextClosedEvent() {
        runner.withPropertyValues(ADDRESS).run(context -> {
            // context close 第一时机（bean 销毁前）手动发布事件：注销在程序还可用时主动执行
            context.publishEvent(new ContextClosedEvent(context));

            RegistryClient bean = context.getBean(RegistryClient.class);
            assertThatThrownBy(() -> bean.register(instance()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        });
        // destroy 阶段二次 close 幂等 no-op，容器正常关闭
    }

    @Test
    void should_inject_boot_meter_registry_when_present() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withPropertyValues(ADDRESS)
                .withBean(SimpleMeterRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(registry.find("artemis.client.instances").gauge())
                            .isNotNull();
                    // 注入宿主 registry 时不得同时写 global（防重复指标序列）
                    assertThat(Metrics.globalRegistry
                                    .find("artemis.client.instances")
                                    .gauge())
                            .isNull();
                });
    }

    @Test
    void should_fallback_to_global_registry_when_no_meter_registry() {
        runner.withPropertyValues(ADDRESS)
                .run(context -> assertThat(Metrics.globalRegistry
                                .find("artemis.client.instances")
                                .gauge())
                        .isNotNull());
    }

    @Test
    void should_fail_fast_when_server_addresses_missing() {
        runner.run(
                context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage(
                                "artemis.client.registry.addresses must be set to valid http(s) url(s), e.g. http://artemis.example.com (missing or invalid — check WARN log for rejected values)"));
    }

    @Test
    void should_not_create_client_when_enabled_false() {
        runner.withPropertyValues(ADDRESS, "artemis.client.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RegistryClient.class));
    }

    @Test
    void should_allow_host_override_when_custom_client_bean_present() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostClientConfiguration.class, ArtemisClientAutoConfiguration.class)
                .withPropertyValues(ADDRESS)
                .run(context ->
                        assertThat(context.getBean(RegistryClient.class)).isSameAs(HostClientConfiguration.CLIENT));
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

    private static ServiceInstance instance() {
        return ServiceInstance.newBuilder()
                .service(ServiceId.newBuilder().namespace("ns").service("svc").build())
                .instanceId("i-1")
                .host("127.0.0.1")
                .port(8080)
                .build();
    }

    private static final class StubClient implements RegistryClient {

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

    @Configuration(proxyBeanMethods = false)
    static class HostClientConfiguration {

        static final StubClient CLIENT = new StubClient();

        @Bean
        RegistryClient customClient() {
            return CLIENT;
        }
    }
}
