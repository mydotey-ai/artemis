package org.mydotey.ai.artemis.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RelaxedEnvironmentConfigurationSourceTest {

    @Test
    void should_map_property_key_to_environment_name() {
        assertThat(RelaxedEnvironmentConfigurationSource.toEnvironmentName("artemis.registry.lease.ttlMs"))
                .isEqualTo("ARTEMIS_REGISTRY_LEASE_TTL_MS");
        assertThat(RelaxedEnvironmentConfigurationSource.toEnvironmentName("artemis.registry.selfProtection.minCount"))
                .isEqualTo("ARTEMIS_REGISTRY_SELF_PROTECTION_MIN_COUNT");
        assertThat(RelaxedEnvironmentConfigurationSource.toEnvironmentName("artemis.registry.auth.enabled"))
                .isEqualTo("ARTEMIS_REGISTRY_AUTH_ENABLED");
    }
}
