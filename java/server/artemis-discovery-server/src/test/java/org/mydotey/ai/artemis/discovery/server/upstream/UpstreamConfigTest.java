package org.mydotey.ai.artemis.discovery.server.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** JSON map 多上游配置（phase-5）：解析与 fail-fast（converter 非法值返回 null 收敛到构造器）。 */
class UpstreamConfigTest {

    private static final String KEY = "artemis.discovery.registry.clusters";

    @Test
    void should_parse_multi_cluster_groups() {
        UpstreamConfig config = new UpstreamConfig(properties(
                Map.of(KEY, "{\"cluster-a\":\"10.0.0.1:9093,10.0.0.2:9093\",\"cluster-b\":\"10.0.1.1:9093\"}")));

        assertThat(config.upstreams()).hasSize(2);
        UpstreamConfig.Upstream a = upstream(config, "cluster-a");
        assertThat(a.endpoints())
                .containsExactly(
                        new UpstreamConfig.Endpoint("10.0.0.1", 9093), new UpstreamConfig.Endpoint("10.0.0.2", 9093));
        assertThat(upstream(config, "cluster-b").endpoints())
                .containsExactly(new UpstreamConfig.Endpoint("10.0.1.1", 9093));
    }

    @Test
    void should_fail_fast_when_clusters_missing_or_malformed() {
        // 未配置
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of()))).isInstanceOf(IllegalStateException.class);
        // 空值
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, ""))))
                .isInstanceOf(IllegalStateException.class);
        // JSON 语法错（converter 返回 null → 构造器 fail-fast）
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":"))))
                .isInstanceOf(IllegalStateException.class);
        // 值为数组（端点应为逗号分隔字符串）
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":[\"10.0.0.1:9093\"]}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_fail_fast_when_cluster_id_blank() {
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\" \":\"10.0.0.1:9093\"}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_fail_fast_when_endpoints_empty_or_invalid() {
        // 端点串空
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":\"\"}"))))
                .isInstanceOf(IllegalStateException.class);
        // 全空白项
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":\" , \"}"))))
                .isInstanceOf(IllegalStateException.class);
        // 缺端口
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":\"10.0.0.1\"}"))))
                .isInstanceOf(IllegalStateException.class);
        // 端口非数字
        assertThatThrownBy(() -> new UpstreamConfig(properties(Map.of(KEY, "{\"cluster-a\":\"10.0.0.1:abc\"}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static UpstreamConfig.Upstream upstream(UpstreamConfig config, String clusterId) {
        return config.upstreams().stream()
                .filter(upstream -> upstream.clusterId().equals(clusterId))
                .findFirst()
                .orElseThrow();
    }

    /** 内存 SCF source（模式同 discovery-core TestProperties，模块内自备）。 */
    private static StringProperties properties(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        return new StringProperties(ConfigurationManagers.newManager("test-discovery-server", Map.of(1, source)));
    }
}
