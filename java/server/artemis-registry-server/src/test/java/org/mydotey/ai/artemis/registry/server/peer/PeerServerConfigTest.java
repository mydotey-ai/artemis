package org.mydotey.ai.artemis.registry.server.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;

class PeerServerConfigTest {

    @Test
    void should_use_defaults_when_not_configured() {
        PeerServerConfig config = new PeerServerConfig(TestProperties.empty());

        assertThat(config.port()).isEqualTo(9093);
        // 地址无默认值；未配置时绑定所有网卡（peer 跨节点可达）
        assertThat(config.address()).isEmpty();
        assertThat(config.bindAddress()).isEqualTo("0.0.0.0:9093");
        assertThat(config.maxInboundMessageBytes()).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    void should_read_explicit_port_and_address_when_configured() {
        PeerServerConfig config = new PeerServerConfig(TestProperties.of(Map.of(
                        PeerServerConfig.KEY_PORT, "19093",
                        PeerServerConfig.KEY_ADDRESS, "10.0.0.1"))
                .properties());

        assertThat(config.bindAddress()).isEqualTo("10.0.0.1:19093");
    }

    @Test
    void should_bracket_bare_ipv6_address_when_building_bind_address() {
        PeerServerConfig config = new PeerServerConfig(TestProperties.of(Map.of(
                        PeerServerConfig.KEY_ADDRESS, "::1",
                        PeerServerConfig.KEY_PORT, "19093"))
                .properties());

        assertThat(config.bindAddress()).isEqualTo("[::1]:19093");
    }

    @Test
    void should_fall_back_to_default_when_port_invalid_or_out_of_range() {
        // SCF 既有语义：非法/越界值（含 0 动态端口）回落默认值，不阻断启动
        for (String value : new String[] {"0", "70000", "abc"}) {
            assertThat(new PeerServerConfig(TestProperties.of(Map.of(PeerServerConfig.KEY_PORT, value))
                                    .properties())
                            .port())
                    .isEqualTo(9093);
        }
    }
}
