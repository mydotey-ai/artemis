package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.server.TestProperties;

class RegistryClientConfigTest {

    @Test
    void should_have_no_default_endpoint_when_no_config() {
        RegistryClientConfig config = new RegistryClientConfig(TestProperties.empty());

        // 端点无默认值；未配置时视为上游未就绪
        assertThat(config.isConfigured()).isFalse();
        assertThat(config.endpoint()).isEmpty();
        // 内部认证默认关闭：不提供默认凭证
        assertThat(config.username()).isEmpty();
        assertThat(config.password()).isEmpty();
        assertThat(config.timeoutMs()).isEqualTo(3_000);
    }

    @Test
    void should_read_explicit_values_when_configured() {
        RegistryClientConfig config = new RegistryClientConfig(TestProperties.of(Map.of(
                RegistryClientConfig.KEY_ENDPOINT,
                "registry.internal:9099",
                RegistryClientConfig.KEY_USERNAME,
                "ops",
                RegistryClientConfig.KEY_PASSWORD,
                "s3cret",
                RegistryClientConfig.KEY_TIMEOUT_MS,
                "500")));

        assertThat(config.isConfigured()).isTrue();
        assertThat(config.endpoint()).isEqualTo("registry.internal:9099");
        assertThat(config.username()).isEqualTo("ops");
        assertThat(config.password()).isEqualTo("s3cret");
        assertThat(config.timeoutMs()).isEqualTo(500);
    }

    @Test
    void should_use_latest_credentials_and_timeout_when_changed() {
        TestProperties properties =
                TestProperties.mutable(Map.of(RegistryClientConfig.KEY_ENDPOINT, "registry.internal:9093"));
        RegistryClientConfig config = new RegistryClientConfig(properties.properties());
        assertThat(config.username()).isEmpty();

        properties.set(RegistryClientConfig.KEY_USERNAME, "peer");
        properties.set(RegistryClientConfig.KEY_PASSWORD, "secret");
        properties.set(RegistryClientConfig.KEY_TIMEOUT_MS, "500");

        assertThat(config.username()).isEqualTo("peer");
        assertThat(config.password()).isEqualTo("secret");
        assertThat(config.timeoutMs()).isEqualTo(500);
    }
}
