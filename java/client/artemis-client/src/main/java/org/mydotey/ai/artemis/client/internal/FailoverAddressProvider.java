package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三级地址容灾选址实现：选址池 = 前置寻址刷新的节点列表（{@link #updateNodes}），
 * 高优先级组（UP &gt; UNKNOWN/STARTING &gt; DOWN）内随机；同一节点连续失败达阈值熔断
 * （select 跳过、窗口到期惰性恢复），**全部熔断仍全量随机**（降权不清零，可用性优先）；
 * 熔断触发时回调 circuitListener（DefaultArtemisClient 接 NodeListRefresher::refreshNow，
 * 立即重新前置寻址）。时钟经 {@link LongSupplier} 注入（测试可控）。
 */
final class FailoverAddressProvider implements ServerAddressProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FailoverAddressProvider.class);

    static final String KEY_CIRCUIT_THRESHOLD = "artemis.client.registry.address.circuitThreshold";
    static final String KEY_CIRCUIT_OPEN_MS = "artemis.client.registry.address.circuitOpenMs";

    private static final int DEFAULT_CIRCUIT_THRESHOLD = 3;
    private static final long DEFAULT_CIRCUIT_OPEN_MS = 30_000;

    /** 单节点熔断状态：连续失败计数 + 熔断截止时间（0 = 未熔断）。 */
    private static final class CircuitState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicLong openUntil = new AtomicLong();
    }

    private final org.mydotey.scf.Property<String, Integer> circuitThreshold;
    private final org.mydotey.scf.Property<String, Long> circuitOpenMs;
    private final LongSupplier clock;
    private final MeterRegistry meterRegistry;
    private final String clientId;
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    private volatile List<NodeEndpoint> nodes = new ArrayList<>();
    private volatile Runnable circuitListener;

    FailoverAddressProvider(StringProperties properties, MeterRegistry registry, String clientId) {
        this(properties, registry, clientId, new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    FailoverAddressProvider(StringProperties properties, MeterRegistry registry, String clientId, LongSupplier clock) {
        this.circuitThreshold = properties.getIntProperty(
                KEY_CIRCUIT_THRESHOLD, new RangeValueConfig<>(DEFAULT_CIRCUIT_THRESHOLD, 1, 100));
        this.circuitOpenMs = properties.getLongProperty(
                KEY_CIRCUIT_OPEN_MS, new RangeValueConfig<>(DEFAULT_CIRCUIT_OPEN_MS, 1000L, 600_000L));
        this.clock = clock;
        this.meterRegistry = registry;
        this.clientId = clientId;
    }

    /** 熔断触发回调（立即重寻址）；须在 refresher 构造后注入（构造顺序解耦）。 */
    void setCircuitListener(Runnable listener) {
        this.circuitListener = listener;
    }

    @Override
    public String select(String exclude) {
        List<NodeEndpoint> pool = nodes;
        if (pool.isEmpty()) {
            throw new NoAddressException("registry node list unavailable (bootstrap pending or empty)");
        }
        List<NodeEndpoint> candidates = pool;
        if (exclude != null && pool.size() > 1) {
            candidates = excludeAddress(pool, exclude);
        }
        List<NodeEndpoint> notCircuited = filterCircuited(candidates);
        if (!notCircuited.isEmpty()) {
            candidates = notCircuited; // 全熔断时保留全量（降权不清零）
        }
        return randomFromTopPriority(candidates);
    }

    @Override
    public void markFailed(String address) {
        if (address == null) {
            return;
        }
        CircuitState state = circuits.computeIfAbsent(address, ignored -> new CircuitState());
        int failures = state.consecutiveFailures.incrementAndGet();
        int threshold = circuitThreshold.getValue(); // 单次读：同次调用内判定一致（动态变更窗口）
        boolean wasCircuited = state.openUntil.get() > clock.getAsLong();
        if (failures >= threshold) {
            state.openUntil.set(clock.getAsLong() + circuitOpenMs.getValue());
            if (!wasCircuited) { // 熔断打开事件（未开→开，含动态降阈值后首次达标）：只计一次、只触发一次重寻址
                // tag address（节点归因；序列数以集群节点数为上界，有界）
                meterRegistry
                        .counter("artemis.client.address.circuit.opened", "client", clientId, "address", address)
                        .increment();
                LOG.warn(
                        "node {} failed {} times consecutively, circuit opened for {}ms",
                        address,
                        failures,
                        circuitOpenMs.getValue());
                Runnable listener = circuitListener;
                if (listener != null) {
                    listener.run();
                }
            }
        }
    }

    @Override
    public void markSucceeded(String address) {
        if (address != null) {
            circuits.remove(address);
        }
    }

    @Override
    public void updateNodes(List<NodeEndpoint> updated) {
        List<NodeEndpoint> copy = new ArrayList<>();
        for (NodeEndpoint endpoint : updated) {
            if (endpoint != null && endpoint.address != null && !containsAddress(copy, endpoint.address)) {
                copy.add(endpoint);
            }
        }
        LOG.info("registry node list refreshed: {} endpoint(s)", copy.size());
        nodes = copy;
        circuits.keySet().retainAll(addressesOf(copy)); // 离开列表的地址熔断态无意义，防拓扑变更无界累积
    }

    private static java.util.Set<String> addressesOf(List<NodeEndpoint> endpoints) {
        java.util.Set<String> addresses = new java.util.HashSet<>();
        for (NodeEndpoint endpoint : endpoints) {
            addresses.add(endpoint.address);
        }
        return addresses;
    }

    private boolean isCircuited(String address) {
        CircuitState state = circuits.get(address);
        return state != null && state.openUntil.get() > clock.getAsLong();
    }

    private List<NodeEndpoint> excludeAddress(List<NodeEndpoint> pool, String exclude) {
        List<NodeEndpoint> remaining = new ArrayList<>(pool.size());
        for (NodeEndpoint endpoint : pool) {
            if (!exclude.equals(endpoint.address)) {
                remaining.add(endpoint);
            }
        }
        return remaining.isEmpty() ? pool : remaining; // exclude 为唯一候选时保留（同址重建仍有对账语义）
    }

    private List<NodeEndpoint> filterCircuited(List<NodeEndpoint> pool) {
        List<NodeEndpoint> usable = new ArrayList<>(pool.size());
        for (NodeEndpoint endpoint : pool) {
            if (!isCircuited(endpoint.address)) {
                usable.add(endpoint);
            }
        }
        return usable;
    }

    private String randomFromTopPriority(List<NodeEndpoint> pool) {
        int top = 0;
        for (NodeEndpoint endpoint : pool) {
            if (endpoint.priority > top) {
                top = endpoint.priority;
            }
        }
        List<NodeEndpoint> topGroup = new ArrayList<>(pool.size());
        for (NodeEndpoint endpoint : pool) {
            if (endpoint.priority == top) {
                topGroup.add(endpoint);
            }
        }
        return topGroup.get(ThreadLocalRandom.current().nextInt(topGroup.size())).address;
    }

    private static boolean containsAddress(List<NodeEndpoint> list, String address) {
        for (NodeEndpoint endpoint : list) {
            if (address.equals(endpoint.address)) {
                return true;
            }
        }
        return false;
    }
}
