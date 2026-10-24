package org.mydotey.ai.artemis.discovery.server.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 静态节点目录配置（ADR-020）：host:port 解析、去重 fail-fast、空目录。 */
class NodeDirectoryConfigTest {

    @Test
    void should_parse_static_node_directory_and_fail_fast() {
        NodeDirectoryConfig config = config("discovery-1:9095, [fd00::1]:9095");
        assertThat(config.nodes()).hasSize(2);
        assertThat(config.nodes().get(0).host()).isEqualTo("discovery-1");
        assertThat(config.nodes().get(0).port()).isEqualTo(9095);
        assertThat(config.nodes().get(1).host()).isEqualTo("[fd00::1]");

        assertThat(config("").nodes()).isEmpty();

        assertThatThrownBy(() -> config("bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NodeDirectoryConfig.KEY_NODES);
        assertThatThrownBy(() -> config("n1:70000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NodeDirectoryConfig.KEY_NODES);
        assertThatThrownBy(() -> config("fd00::1:9095"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NodeDirectoryConfig.KEY_NODES);
        assertThatThrownBy(() -> config("n1:9095,n1:9095"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    private static NodeDirectoryConfig config(String nodes) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setPropertyValue(NodeDirectoryConfig.KEY_NODES, nodes);
        StringProperties properties = new StringProperties(ConfigurationManagers.newManager(
                "test-node-directory-" + System.nanoTime(), Collections.singletonMap(1, source)));
        return new NodeDirectoryConfig(properties);
    }
}
