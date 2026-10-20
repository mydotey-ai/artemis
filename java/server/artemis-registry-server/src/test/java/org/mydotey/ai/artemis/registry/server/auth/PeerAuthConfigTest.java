package org.mydotey.ai.artemis.registry.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;

class PeerAuthConfigTest {

    @Test
    void should_disable_internal_auth_by_default_when_not_configured() {
        PeerAuthConfig config = new PeerAuthConfig(TestProperties.empty());

        assertThat(config.enabled()).isFalse();
        assertThat(config.username()).isEmpty();
        assertThat(config.password()).isEmpty();
    }

    @Test
    void should_fail_fast_when_internal_auth_enabled_without_credentials() {
        assertThatThrownBy(() -> new PeerAuthConfig(TestProperties.of(Map.of(PeerAuthConfig.KEY_ENABLED, "true"))
                        .properties()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PeerAuthConfig.KEY_USERNAME);
    }

    @Test
    void should_treat_non_true_value_as_disabled_per_scf_semantics() {
        PeerAuthConfig config = new PeerAuthConfig(
                TestProperties.of(Map.of(PeerAuthConfig.KEY_ENABLED, "yes")).properties());

        assertThat(config.enabled()).isFalse();
    }

    @Test
    void should_use_latest_credentials_when_changed() {
        TestProperties properties = TestProperties.of(Map.of(
                PeerAuthConfig.KEY_ENABLED, "true",
                PeerAuthConfig.KEY_USERNAME, "peer",
                PeerAuthConfig.KEY_PASSWORD, "secret"));
        PeerAuthConfig config = new PeerAuthConfig(properties.properties());

        properties.set(PeerAuthConfig.KEY_USERNAME, "rotated");
        properties.set(PeerAuthConfig.KEY_PASSWORD, "n3w-secret");

        assertThat(config.username()).isEqualTo("rotated");
        assertThat(config.password()).isEqualTo("n3w-secret");
    }

    @Test
    void should_accept_configured_credentials_when_enabled() {
        PeerAuthConfig config = new PeerAuthConfig(TestProperties.of(Map.of(
                        PeerAuthConfig.KEY_ENABLED, "true",
                        PeerAuthConfig.KEY_USERNAME, "peer",
                        PeerAuthConfig.KEY_PASSWORD, "secret"))
                .properties());

        assertThat(config.enabled()).isTrue();
        assertThat(config.username()).isEqualTo("peer");
        assertThat(config.password()).isEqualTo("secret");
    }
}
