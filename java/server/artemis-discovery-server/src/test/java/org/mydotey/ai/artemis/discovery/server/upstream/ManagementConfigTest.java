package org.mydotey.ai.artemis.discovery.server.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** management 规则流配置（phase-6，0..1）：未配置不启用、合法解析、非法 fail-fast。 */
class ManagementConfigTest {

    private static final String KEY = "artemis.discovery.management.endpoints";

    @Test
    void should_be_disabled_when_not_configured() {
        assertThat(new ManagementConfig(properties(Map.of())).enabled()).isFalse();
        assertThat(new ManagementConfig(properties(Map.of(KEY, " "))).enabled()).isFalse();
        assertThat(new ManagementConfig(properties(Map.of())).endpoints()).isEmpty();
    }

    @Test
    void should_parse_endpoint_list() {
        ManagementConfig config = new ManagementConfig(properties(Map.of(KEY, "10.0.0.1:9094, 10.0.0.2:9094")));

        assertThat(config.enabled()).isTrue();
        assertThat(config.endpoints())
                .containsExactly(
                        new UpstreamConfig.Endpoint("10.0.0.1", 9094), new UpstreamConfig.Endpoint("10.0.0.2", 9094));
    }

    @Test
    void should_fail_fast_when_endpoints_invalid() {
        // 空白项
        assertThatThrownBy(() -> new ManagementConfig(properties(Map.of(KEY, "10.0.0.1:9094, "))))
                .isInstanceOf(IllegalStateException.class);
        // 缺端口
        assertThatThrownBy(() -> new ManagementConfig(properties(Map.of(KEY, "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class);
        // 非数字端口
        assertThatThrownBy(() -> new ManagementConfig(properties(Map.of(KEY, "10.0.0.1:http"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static StringProperties properties(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        return new StringProperties(ConfigurationManagers.newManager("test", Map.of(1, source)));
    }
}
