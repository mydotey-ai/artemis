package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 发现侧选址：历史成功地址优先（sticky）+ 熔断换址 + exclude。 */
class DiscoveryAddressProviderTest {

    private static final long STEP_MS = 10_000;

    @Test
    void should_prefer_last_successful_then_circuit_address() {
        AtomicLong now = new AtomicLong(1_000_000);
        FailoverAddressProvider provider = provider("circuitThreshold=2,circuitOpenMs=30000", now);
        provider.updateNodes(endpoints("n1:1", "n2:2"));

        provider.markSucceeded("n1:1");
        assertThat(provider.select(null)).isEqualTo("n1:1"); // sticky

        provider.markFailed("n1:1");
        provider.markFailed("n1:1"); // 达阈值熔断
        assertThat(provider.select(null)).isEqualTo("n2:2"); // 熔断跳过 n1

        now.addAndGet(31_000); // 窗口到期惰性恢复；最近成功仍是 n1
        assertThat(provider.select(null)).isEqualTo("n1:1");

        assertThat(provider.select("n1:1")).isEqualTo("n2:2"); // 重连换址排除当前
    }

    private static FailoverAddressProvider provider(String config, AtomicLong clock) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        for (String entry : config.split(",")) {
            String[] pair = entry.split("=", 2);
            String key;
            if ("circuitThreshold".equals(pair[0])) {
                key = FailoverAddressProvider.DISCOVERY_ADDRESS_PREFIX + ".circuitThreshold";
            } else if ("circuitOpenMs".equals(pair[0])) {
                key = FailoverAddressProvider.DISCOVERY_ADDRESS_PREFIX + ".circuitOpenMs";
            } else {
                throw new IllegalArgumentException("unknown test config: " + pair[0]);
            }
            source.setPropertyValue(key, pair[1]);
        }
        StringProperties properties = new StringProperties(ConfigurationManagers.newManager(
                "test-discovery-address-" + System.nanoTime(), Collections.singletonMap(1, source)));
        return new FailoverAddressProvider(
                properties,
                new SimpleMeterRegistry(),
                "test",
                FailoverAddressProvider.DISCOVERY_ADDRESS_PREFIX,
                true,
                clock::get);
    }

    private static List<NodeEndpoint> endpoints(String... addresses) {
        List<NodeEndpoint> endpoints = new ArrayList<>();
        for (String address : addresses) {
            endpoints.add(new NodeEndpoint(address, NodeEndpoint.PRIORITY_UP));
        }
        return endpoints;
    }
}
