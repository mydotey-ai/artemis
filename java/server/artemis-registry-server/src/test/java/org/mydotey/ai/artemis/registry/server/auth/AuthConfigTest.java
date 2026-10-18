package org.mydotey.ai.artemis.registry.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;

class AuthConfigTest {

    @Test
    void should_use_default_account_when_no_source_value() {
        AuthConfig config = new AuthConfig(TestProperties.empty());

        assertThat(config.enabled()).isTrue();
        assertThat(config.username()).isEqualTo("artemis");
        assertThat(config.password()).isEqualTo("artemis");
    }

    @Test
    void should_read_explicit_credentials_when_configured() {
        AuthConfig config = new AuthConfig(TestProperties.of(Map.of(
                        AuthConfig.KEY_ENABLED, "true",
                        AuthConfig.KEY_USERNAME, "ops",
                        AuthConfig.KEY_PASSWORD, "s3cret"))
                .properties());

        assertThat(config.enabled()).isTrue();
        assertThat(config.username()).isEqualTo("ops");
        assertThat(config.password()).isEqualTo("s3cret");
    }

    @Test
    void should_disable_auth_when_explicitly_false() {
        AuthConfig config = new AuthConfig(
                TestProperties.of(Map.of(AuthConfig.KEY_ENABLED, "false")).properties());

        assertThat(config.enabled()).isFalse();
    }

    @Test
    void should_use_builtin_boolean_semantics_when_value_not_true() {
        // SCF 内置 StringToBooleanConverter（Boolean.parseBoolean）：仅 "true" 为 true
        AuthConfig config = new AuthConfig(
                TestProperties.of(Map.of(AuthConfig.KEY_ENABLED, "yes")).properties());

        assertThat(config.enabled()).isFalse();
    }
}
