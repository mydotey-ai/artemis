package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.TestAwait;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 前置寻址调度：周期稳态、失败退避、熔断触发立即刷新、失败保留旧列表（勘误 §8-5）。 */
class NodeListRefresherTest {

    private final List<NodeListRefresher> refreshers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NodeListRefresher refresher : refreshers) {
            refresher.close();
        }
    }

    @Test
    void should_keepStaleNodeList_when_refreshFails() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.next = endpoints("n1:1", "n2:2");
        FailoverAddressProvider provider = startRefresher(fetcher, "refreshIntervalMs=1000");

        TestAwait.await("initial list applied", 3000, () -> provider.select(null) != null);
        fetcher.next = null; // 后续刷新全部失败

        TestAwait.await("failed refresh attempted", 5000, () -> fetcher.calls.get() >= 2);
        // 失败不清空：选址池仍为旧列表
        assertThat(provider.select(null)).isIn("n1:1", "n2:2");
    }

    @Test
    void should_refreshNodeListImmediately_when_circuitOpened() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.next = endpoints("n1:1");
        FailoverAddressProvider provider = startRefresher(fetcher, "refreshIntervalMs=3600000"); // 周期拉大：只验熔断触发

        TestAwait.await("initial fetch", 3000, () -> !fetcher.callTimes.isEmpty());
        int callsAtCircuit = fetcher.calls.get();
        long lastCallTime = fetcher.callTimes.peekLast();

        provider.markFailed("n1:1"); // 阈值默认 3 不够快，直接连打 3 次
        provider.markFailed("n1:1");
        provider.markFailed("n1:1"); // 熔断 → refreshNow 立即寻址（不等 1h 周期）

        TestAwait.await(
                "immediate refresh after circuit opened",
                3000,
                () -> fetcher.calls.get() > callsAtCircuit
                        && fetcher.callTimes.peekLast() - lastCallTime < 10_000_000_000L);
    }

    @Test
    void should_backoffRetries_when_bootstrapKeepsFailing() {
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.next = null; // 持续失败
        startRefresher(fetcher, "refreshIntervalMs=1000,retryBaseMs=100,retryMaxMs=2000");

        TestAwait.await("backoff retries issued", 8000, () -> fetcher.calls.get() >= 4);
        // 退避间隔不递减（指数 + 抖动）
        long[] times = new long[4];
        int i = 0;
        for (Long time : fetcher.callTimes) {
            if (i < 4) {
                times[i++] = time;
            }
        }
        long firstGap = times[1] - times[0];
        long laterGap = times[3] - times[2];
        assertThat(laterGap).isGreaterThanOrEqualTo(firstGap);

        // 成功后退避归零、回到稳态周期节奏（1000ms 周期 >> 400ms 退避上限）
        fetcher.next = endpoints("n1:1");
        int callsAtRecovery = fetcher.calls.get();
        TestAwait.await("steady interval resumed", 8000, () -> fetcher.calls.get() > callsAtRecovery + 1);
    }

    @Test
    void should_keepSingleChain_when_refreshNowDuringSlowRefresh() throws Exception {
        // 慢寻址执行中触发 refreshNow（熔断风暴连发）：运行中 refresh 尾部让位，不得排出第二条周期链
        java.util.concurrent.CountDownLatch enteredFirstFetch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstFetch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        NodeListFetcher fetcher = new NodeListFetcher() {
            @Override
            public List<NodeEndpoint> fetch() throws Exception {
                calls.incrementAndGet();
                if (calls.get() == 1) {
                    enteredFirstFetch.countDown();
                    releaseFirstFetch.await(5, java.util.concurrent.TimeUnit.SECONDS); // 模拟慢寻址（url 超时窗）
                }
                return endpoints("n1:1");
            }
        };
        StringProperties properties = properties("refreshIntervalMs=1000");
        FailoverAddressProvider provider =
                new FailoverAddressProvider(properties, new SimpleMeterRegistry(), "single-chain");
        NodeListRefresher refresher =
                new NodeListRefresher(fetcher, provider, properties, new SimpleMeterRegistry(), "single-chain");
        provider.setCircuitListener(refresher::refreshNow);
        refreshers.add(refresher);
        refresher.start();

        assertThat(enteredFirstFetch.await(3, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();
        refresher.refreshNow(); // 慢 refresh 进行中：立即任务排队，运行中 refresh 尾部须让位
        refresher.refreshNow();
        releaseFirstFetch.countDown();

        // 稳态周期 1000ms：观察 2 个周期的调用增量——双链会以 ~2×周期速率增长
        TestAwait.await("immediate refresh applied", 3000, () -> calls.get() >= 2);
        Thread.sleep(1200); // 稳定到周期节奏
        int baseline = calls.get();
        Thread.sleep(2100);
        assertThat(calls.get() - baseline).isLessThanOrEqualTo(3); // 单链 ≈2 次/2 周期（调度余量 +1）；双链 ≥4
    }

    // ---- 设施 ----

    /** provider + refresher 同源 properties 装配（对齐 DefaultArtemisClient 组装），返回 provider 供断言。 */
    private FailoverAddressProvider startRefresher(NodeListFetcher fetcher, String config) {
        StringProperties properties = properties(config);
        FailoverAddressProvider provider =
                new FailoverAddressProvider(properties, new SimpleMeterRegistry(), "test-provider");
        NodeListRefresher refresher =
                new NodeListRefresher(fetcher, provider, properties, new SimpleMeterRegistry(), "test-refresher");
        provider.setCircuitListener(refresher::refreshNow);
        refreshers.add(refresher);
        refresher.start();
        return provider;
    }

    private static List<NodeEndpoint> endpoints(String... addresses) {
        List<NodeEndpoint> endpoints = new ArrayList<>();
        for (String address : addresses) {
            endpoints.add(new NodeEndpoint(address, NodeEndpoint.PRIORITY_UP));
        }
        return endpoints;
    }

    private static StringProperties properties(String config) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        for (String entry : config.split(",")) {
            String[] pair = entry.split("=", 2);
            source.setPropertyValue(keyOf(pair[0]), pair[1]);
        }
        return new StringProperties(ConfigurationManagers.newManager(
                "test-refresher-" + System.nanoTime(), java.util.Collections.singletonMap(1, source)));
    }

    private static String keyOf(String name) {
        if ("refreshIntervalMs".equals(name)) {
            return NodeListRefresher.KEY_REFRESH_INTERVAL_MS;
        }
        if ("retryBaseMs".equals(name)) {
            return NodeListRefresher.KEY_RETRY_BASE_MS;
        }
        if ("retryMaxMs".equals(name)) {
            return NodeListRefresher.KEY_RETRY_MAX_MS;
        }
        if ("circuitThreshold".equals(name)) {
            return FailoverAddressProvider.KEY_CIRCUIT_THRESHOLD;
        }
        throw new IllegalArgumentException("unknown test config: " + name);
    }

    /** 可编程 fetcher：next 非空成功、null 抛（记录调用次数与时间戳）。 */
    private static final class RecordingFetcher implements NodeListFetcher {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final ConcurrentLinkedDeque<Long> callTimes = new ConcurrentLinkedDeque<>();
        volatile List<NodeEndpoint> next;

        @Override
        public List<NodeEndpoint> fetch() {
            calls.incrementAndGet();
            callTimes.add(System.nanoTime());
            List<NodeEndpoint> current = next;
            if (current == null) {
                throw new IllegalStateException("fetch failed");
            }
            return current;
        }
    }
}
