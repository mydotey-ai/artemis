package org.mydotey.ai.artemis.management.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.server.TestProperties;

class ManagementAuthConfigTest {

    @Test
    void should_default_auth_enabled_when_no_config() {
        ManagementAuthConfig config = new ManagementAuthConfig(TestProperties.empty());

        assertThat(config.enabled()).isTrue();
        assertThat(config.username()).isEqualTo("artemis");
        assertThat(config.password()).isEqualTo("artemis");
    }

    @Test
    void should_read_explicit_credentials_when_configured() {
        ManagementAuthConfig config = new ManagementAuthConfig(TestProperties.of(Map.of(
                ManagementAuthConfig.KEY_USERNAME, "ops",
                ManagementAuthConfig.KEY_PASSWORD, "s3cret")));

        assertThat(config.username()).isEqualTo("ops");
        assertThat(config.password()).isEqualTo("s3cret");
    }

    @Test
    void should_disable_auth_when_explicitly_false() {
        ManagementAuthConfig config =
                new ManagementAuthConfig(TestProperties.of(Map.of(ManagementAuthConfig.KEY_ENABLED, "false")));

        assertThat(config.enabled()).isFalse();
    }

    @Test
    void should_fall_back_to_default_when_credentials_blank() {
        // SCF 将空白字符串视为未设置（ObjectExtension.isNull → isBlank），回落默认值
        ManagementAuthConfig config = new ManagementAuthConfig(TestProperties.of(
                Map.of(ManagementAuthConfig.KEY_USERNAME, " ", ManagementAuthConfig.KEY_PASSWORD, "")));

        assertThat(config.username()).isEqualTo("artemis");
        assertThat(config.password()).isEqualTo("artemis");
    }
}
