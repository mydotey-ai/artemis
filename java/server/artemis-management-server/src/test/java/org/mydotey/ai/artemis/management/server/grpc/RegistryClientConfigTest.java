package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.server.TestProperties;

class RegistryClientConfigTest {

    @Test
    void should_use_defaults_when_no_config() {
        RegistryClientConfig config = new RegistryClientConfig(TestProperties.empty());

        assertThat(config.endpoint()).isEqualTo("localhost:9090");
        assertThat(config.username()).isEqualTo("artemis");
        assertThat(config.password()).isEqualTo("artemis");
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

        assertThat(config.endpoint()).isEqualTo("registry.internal:9099");
        assertThat(config.username()).isEqualTo("ops");
        assertThat(config.password()).isEqualTo("s3cret");
        assertThat(config.timeoutMs()).isEqualTo(500);
    }

    @Test
    void should_fall_back_to_default_when_endpoint_blank() {
        // SCF 将空白字符串视为未设置，回落默认端点
        RegistryClientConfig config =
                new RegistryClientConfig(TestProperties.of(Map.of(RegistryClientConfig.KEY_ENDPOINT, " ")));

        assertThat(config.endpoint()).isEqualTo("localhost:9090");
    }
}
