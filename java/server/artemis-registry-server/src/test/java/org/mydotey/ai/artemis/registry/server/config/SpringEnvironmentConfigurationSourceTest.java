package org.mydotey.ai.artemis.registry.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.mock.env.MockEnvironment;

class SpringEnvironmentConfigurationSourceTest {

    @Test
    void should_expose_spring_environment_properties_to_scf() {
        MockEnvironment environment = new MockEnvironment().withProperty("artemis.registry.lease.ttlMs", "5000");
        StringProperties properties = new StringProperties(RegistryConfigurationSources.manager(environment));

        // 命令行参数 / Spring 属性优先于默认值
        assertThat(properties
                        .getStringProperty("artemis.registry.lease.ttlMs", "30000")
                        .getValue())
                .isEqualTo("5000");
    }
}
