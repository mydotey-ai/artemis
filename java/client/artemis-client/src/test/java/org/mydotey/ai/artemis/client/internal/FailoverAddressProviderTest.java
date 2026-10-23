package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 选址内核：节点列表选址池、UP 优先、熔断开/跳过/恢复（降权不清零）、exclude 换址。 */
class FailoverAddressProviderTest {

    private static final long STEP_MS = 10_000; // 可控时钟步进粒度

    @Test
    void should_selectFromNodeList_when_fetched() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        assertThatThrownBy(() -> provider.select(null)).isInstanceOf(NoAddressException.class);

        provider.updateNodes(endpoints("n1:1", "n2:2"));
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            picked.add(provider.select(null));
        }
        assertThat(picked).containsExactlyInAnyOrder("n1:1", "n2:2"); // 列表即选址池
    }

    @Test
    void should_preferUpState_when_nodeListMixed() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        provider.updateNodes(new ArrayList<>(java.util.Arrays.asList(
                new NodeEndpoint("up:1", NodeEndpoint.PRIORITY_UP),
                new NodeEndpoint("unknown:2", NodeEndpoint.PRIORITY_ALIVE),
                new NodeEndpoint("down:3", NodeEndpoint.PRIORITY_DOWN))));

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            picked.add(provider.select(null));
        }
        assertThat(picked).containsExactly("up:1"); // UP 组优先，不落低优

        provider.updateNodes(new ArrayList<>(java.util.Arrays.asList(
                new NodeEndpoint("unknown:2", NodeEndpoint.PRIORITY_ALIVE),
                new NodeEndpoint("down:3", NodeEndpoint.PRIORITY_DOWN))));
        picked.clear();
        for (int i = 0; i < 50; i++) {
            picked.add(provider.select(null));
        }
        assertThat(picked).containsExactly("unknown:2"); // 无 UP 落 UNKNOWN/STARTING 组
    }

    @Test
    void should_openCircuit_when_consecutiveFailuresReachThreshold() {
        AtomicLong fired = new AtomicLong();
        FailoverAddressProvider provider = provider("circuitThreshold=3", clock());
        provider.setCircuitListener(fired::incrementAndGet);
        provider.updateNodes(endpoints("n1:1", "n2:2"));

        provider.markFailed("n1:1");
        provider.markFailed("n1:1");
        assertThat(fired.get()).isZero(); // 未达阈值不熔断不触发
        provider.markFailed("n1:1"); // 第 3 次：熔断 + 触发重寻址回调

        assertThat(fired.get()).isEqualTo(1);
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            picked.add(provider.select(null));
        }
        assertThat(picked).containsExactly("n2:2"); // 熔断地址被跳过
    }

    @Test
    void should_skipCircuitedAddress_untilWindowExpires() {
        AtomicLong now = new AtomicLong(1_000_000);
        FailoverAddressProvider provider = provider("circuitThreshold=1,circuitOpenMs=10000", clock(now));
        provider.updateNodes(endpoints("n1:1", "n2:2"));
        provider.markFailed("n1:1");

        assertThat(selectCounts(provider, "n1:1", 50)).isFalse(); // 熔断窗口内跳过

        now.addAndGet(10_001); // 窗口到期：惰性恢复
        assertThat(selectCounts(provider, "n1:1", 50)).isTrue();
    }

    @Test
    void should_selectFromAll_when_allCircuited() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        provider.updateNodes(endpoints("n1:1", "n2:2"));
        provider.markFailed("n1:1");
        provider.markFailed("n2:2");

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            picked.add(provider.select(null));
        }
        assertThat(picked).containsExactlyInAnyOrder("n1:1", "n2:2"); // 全熔断全量随机（降权不清零）
    }

    @Test
    void should_recoverCircuit_when_succeeded() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        provider.updateNodes(endpoints("n1:1", "n2:2"));
        provider.markFailed("n1:1");
        provider.markSucceeded("n1:1"); // 清零 + 解除熔断

        assertThat(selectCounts(provider, "n1:1", 50)).isTrue();
    }

    @Test
    void should_excludeCurrentAddress_when_alternativesExist() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        provider.updateNodes(endpoints("n1:1", "n2:2", "n3:3"));

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            picked.add(provider.select("n2:2")); // 重连/TTL 轮换：排除当前地址
        }
        assertThat(picked).containsExactlyInAnyOrder("n1:1", "n3:3");
    }

    @Test
    void should_returnExcludedAddress_when_soleCandidate() {
        FailoverAddressProvider provider = provider("circuitThreshold=1", clock());
        provider.updateNodes(endpoints("n1:1"));

        assertThat(provider.select("n1:1")).isEqualTo("n1:1"); // 唯一候选：同址重建（对账语义保留）
    }

    // ---- 设施 ----

    private static LongSupplier clock() {
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return 1_000_000;
            }
        };
    }

    private static LongSupplier clock(final AtomicLong now) {
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return now.get();
            }
        };
    }

    private static boolean selectCounts(FailoverAddressProvider provider, String address, int attempts) {
        for (int i = 0; i < attempts; i++) {
            if (address.equals(provider.select(null))) {
                return true;
            }
        }
        return false;
    }

    private static List<NodeEndpoint> endpoints(String... addresses) {
        List<NodeEndpoint> endpoints = new ArrayList<>();
        for (String address : addresses) {
            endpoints.add(new NodeEndpoint(address, NodeEndpoint.PRIORITY_UP));
        }
        return endpoints;
    }

    /** key=value 逗号串进 memory 源（测试压参：熔断阈值/窗口）。 */
    private static FailoverAddressProvider provider(String config, LongSupplier clock) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        for (String entry : config.split(",")) {
            String[] pair = entry.split("=", 2);
            String key;
            if ("circuitThreshold".equals(pair[0])) {
                key = FailoverAddressProvider.KEY_CIRCUIT_THRESHOLD;
            } else if ("circuitOpenMs".equals(pair[0])) {
                key = FailoverAddressProvider.KEY_CIRCUIT_OPEN_MS;
            } else {
                throw new IllegalArgumentException("unknown test config: " + pair[0]);
            }
            source.setPropertyValue(key, pair[1]);
        }
        StringProperties properties = new StringProperties(ConfigurationManagers.newManager(
                "test-failover-" + System.nanoTime(), java.util.Collections.singletonMap(1, source)));
        return new FailoverAddressProvider(properties, new SimpleMeterRegistry(), "test", clock);
    }
}
