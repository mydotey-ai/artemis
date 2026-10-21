package org.mydotey.ai.artemis.registry.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 自我保护 2.0：节点级兜底 + 服务级双层窗口（ADR-017）。
 *
 * <p>节点级窗口沿用 v0.1 算法（1s 分桶环形数组 + LongAdder、历史峰值、minCount 门槛、
 * factor 阈值），作为网络分区/小服务/纯副本兜底；服务级窗口按 ServiceKey 独立统计，
 * 历史峰值达到 minCount 后服务级状态权威，否则过期清理回落节点级状态。
 *
 * <p>保护态是显式状态机（NORMAL/PROTECTING）：只有周期评估 {@link #evaluate(Set)} 能迁移状态、
 * 注册/移除服务级 gauge 与回收窗口；查询与 Prometheus 抓取只读 volatile 状态，无副作用。
 *
 * <p>回落：节点健康时，服务窗口峰值超过 {@code maxCountResetMs} 未抬升则回落到当前窗口量，
 * 限制「单服务客户端迁移到其他节点」造成的永久保护；节点级保护中不回落，保留分区兜底。
 *
 * <p>时间源（D14）：窗口推进、峰值回落与保护时长取 {@link MonotonicClock}。
 */
@Slf4j
public final class SelfProtection {

    public static final String KEY_WINDOW_SECONDS = "artemis.registry.selfProtection.windowSeconds";
    public static final String KEY_MIN_COUNT = "artemis.registry.selfProtection.minCount";
    public static final String KEY_FACTOR = "artemis.registry.selfProtection.factor";
    public static final String KEY_MAX_COUNT_RESET_MS = "artemis.registry.selfProtection.maxCountResetMs";

    private static final int DEFAULT_WINDOW_SECONDS = 10;
    /** 分桶环动态增长上限；窗口长度动态可调（≤ 上限）。 */
    private static final int MAX_WINDOW_SECONDS = 300;

    private static final int DEFAULT_MIN_COUNT = 50;
    private static final double DEFAULT_FACTOR = 0.85;
    private static final long DEFAULT_MAX_COUNT_RESET_MS = 600_000;

    /** 服务从注册表消失后的清理宽限：避免与并发重注册/首次心跳竞态。 */
    private static final long CLEANUP_GRACE_MS = 1_000;

    private static final String METRIC_NODE_PROTECTION = "artemis.registry.self.protection";
    private static final String METRIC_NODE_RENEWALS = "artemis.registry.self.renewals";
    private static final String METRIC_NODE_MAX = "artemis.registry.self.max";
    private static final String METRIC_SERVICE_PROTECTION = "artemis.registry.self.service.protection";
    private static final String METRIC_SERVICE_PROTECTION_COUNT = "artemis.registry.self.service.protection.count";
    private static final String METRIC_SERVICE_TRANSITIONS = "artemis.registry.self.service.protection.transitions";

    private static final Comparator<ServiceKey> SERVICE_ORDER =
            Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service);

    private final Property<String, Integer> windowSeconds;
    private final Property<String, Integer> minCount;
    private final Property<String, Double> factor;
    private final Property<String, Long> maxCountResetMs;
    private final MonotonicClock clock;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<ServiceKey, Window> windows = new ConcurrentHashMap<>();
    private final Window nodeWindow;
    private final AtomicInteger protectingServices = new AtomicInteger();
    private final Counter transitionsProtecting;
    private final Counter transitionsNormal;
    private volatile boolean nodeProtecting;
    /** 本轮评估读取的 minCount：判定与清理谓词共用同一口径，避免逐服务读动态配置。 */
    private volatile int evaluatedMinCount;

    /**
     * 自我保护参数全部为动态 property：窗口长度每次聚合时读取（分桶环按需增长），
     * 启用下限、阈值因子与回落间隔同样动态生效。
     */
    public SelfProtection(StringProperties properties, MeterRegistry meterRegistry, MonotonicClock clock) {
        this.windowSeconds = properties.getIntProperty(
                KEY_WINDOW_SECONDS, new RangeValueConfig<>(DEFAULT_WINDOW_SECONDS, 1, MAX_WINDOW_SECONDS));
        this.minCount =
                properties.getIntProperty(KEY_MIN_COUNT, new RangeValueConfig<>(DEFAULT_MIN_COUNT, 0, 1_000_000));
        this.factor = properties.getDoubleProperty(KEY_FACTOR, new RangeValueConfig<>(DEFAULT_FACTOR, 0.1, 1.0));
        this.maxCountResetMs = properties.getLongProperty(
                KEY_MAX_COUNT_RESET_MS, new RangeValueConfig<>(DEFAULT_MAX_COUNT_RESET_MS, 1_000L, 86_400_000L));
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.evaluatedMinCount = this.minCount.getValue();
        this.nodeWindow = new Window(null);
        registerNodeMetrics();
        this.transitionsProtecting = Counter.builder(METRIC_SERVICE_TRANSITIONS)
                .description("service self-protection state transitions")
                .tag("to", "protecting")
                .register(meterRegistry);
        this.transitionsNormal = Counter.builder(METRIC_SERVICE_TRANSITIONS)
                .description("service self-protection state transitions")
                .tag("to", "normal")
                .register(meterRegistry);
        Gauge.builder(METRIC_SERVICE_PROTECTION_COUNT, this, p -> p.protectingServices.get())
                .description("number of services currently in self-protection")
                .register(meterRegistry);
    }

    /** 节点级指标沿用 v0.1 名称（监控属实现关注点，不入领域接口）。 */
    private void registerNodeMetrics() {
        Gauge.builder(METRIC_NODE_PROTECTION, nodeWindow, window -> window.isProtecting() ? 1 : 0)
                .description("node-level self-protection state (1 = protecting)")
                .register(meterRegistry);
        Gauge.builder(METRIC_NODE_RENEWALS, nodeWindow, Window::currentRenewals)
                .description("node-level renewals in current self-protection window")
                .register(meterRegistry);
        Gauge.builder(METRIC_NODE_MAX, nodeWindow, window -> window.maxCount.get())
                .description("node-level historical max renewals in self-protection window")
                .register(meterRegistry);
    }

    /**
     * 记录一次心跳的续约量：同时累加节点级窗口与该服务窗口。
     * 显式 register/update/deregister、复制入站与回放不计——非本地心跳续约信号。
     */
    public void recordRenewals(ServiceKey service, int instanceCount) {
        if (service == null || instanceCount <= 0) {
            return;
        }
        long now = clock.monoMillis();
        long second = now / 1000;
        nodeWindow.add(second, instanceCount, now);
        windows.computeIfAbsent(service, Window::new).add(second, instanceCount, now);
    }

    /**
     * 过期清理判定：服务级窗口达到 minCount 后以服务级状态为准；未达门槛或无本地窗口
     * （副本/新服务）回落到节点级兜底。
     */
    public boolean isProtected(ServiceKey service) {
        Window window = windows.get(service);
        if (window != null && window.maxCount.get() >= evaluatedMinCount) {
            return window.isProtecting();
        }
        return nodeProtecting;
    }

    /**
     * 周期评估（唯一状态写入口，同步单写者）：节点级 → 服务级状态迁移 → 服务窗口回收。
     * 由 1s 清理调度在过期清理前调用。
     */
    public synchronized void evaluate(Set<ServiceKey> liveServices) {
        long now = clock.monoMillis();
        // 每轮只读一次动态配置：同一轮内节点级与所有服务级判定口径一致
        int window = windowSeconds.getValue();
        int minCountValue = minCount.getValue();
        double factorValue = factor.getValue();
        long resetMs = maxCountResetMs.getValue();
        evaluatedMinCount = minCountValue;

        nodeWindow.evaluate(now, true, false, window, minCountValue, factorValue, resetMs);
        nodeProtecting = nodeWindow.isProtecting();

        // 回收已消失服务（二次确认窗 + 续约活动检查，避开与并发重注册/心跳的竞态）。
        // remove 在 computeIfPresent 内完成：与 recordRenewals 的 computeIfAbsent 同键串行，
        // 续约先到则保留窗口，续约后到则重建窗口，不会丢弃刚落下的续约。
        for (ServiceKey key : windows.keySet()) {
            if (liveServices != null && liveServices.contains(key)) {
                Window live = windows.get(key);
                if (live != null) {
                    live.absentSinceMs = -1;
                }
                continue;
            }
            windows.computeIfPresent(key, (ignored, windowState) -> {
                if (windowState.absentSinceMs < 0) {
                    windowState.absentSinceMs = now;
                }
                if (windowState.lastRenewalMs > windowState.absentSinceMs) {
                    // 标记缺席后仍有续约（并发心跳/重注册）：以最后一次续约为准重新计时
                    windowState.absentSinceMs = windowState.lastRenewalMs;
                }
                if (now - windowState.absentSinceMs >= CLEANUP_GRACE_MS) {
                    windowState.unregisterGauge();
                    return null;
                }
                return windowState;
            });
        }

        int protecting = 0;
        for (Window windowState : windows.values()) {
            windowState.evaluate(now, false, nodeProtecting, window, minCountValue, factorValue, resetMs);
            if (windowState.isProtecting()) {
                protecting++;
            }
        }
        protectingServices.set(protecting);
    }

    /**
     * 查询快照（只读）：按 (namespace, service) 升序；默认仅 PROTECTING，
     * includeNormal 或指定 service 时包含 NORMAL。
     */
    public List<ServiceProtectionStatus> statuses(String namespace, String service, boolean includeNormal) {
        boolean hasServiceFilter = !isBlank(service);
        int minCountValue = evaluatedMinCount;
        List<ServiceProtectionStatus> result = new ArrayList<>();
        long now = clock.monoMillis();
        for (Window window : windows.values()) {
            if (!matches(window.service.namespace(), namespace) || !matches(window.service.service(), service)) {
                continue;
            }
            boolean protecting = window.isProtecting();
            if (!includeNormal && !hasServiceFilter && !protecting) {
                continue;
            }
            long maxCount = window.maxCount.get();
            result.add(new ServiceProtectionStatus(
                    window.service,
                    window.currentRenewals(),
                    maxCount,
                    maxCount < minCountValue,
                    protecting,
                    protecting ? now - window.protectingSinceMs : 0));
        }
        // 契约：指定 namespace + service 时该服务总是返回；无本地窗口（副本/已回收）
        // 返回零计数条目，node_fallback=true 表示清理判定取节点级状态
        if (hasServiceFilter && result.isEmpty() && !isBlank(namespace)) {
            result.add(new ServiceProtectionStatus(new ServiceKey(namespace, service), 0, 0, true, false, 0));
        }
        result.sort(Comparator.comparing(ServiceProtectionStatus::service, SERVICE_ORDER));
        return result;
    }

    /** 当前有本地心跳窗口的服务数（弱一致）。 */
    public int trackedServiceCount() {
        return windows.size();
    }

    /** 当前保护中服务数（评估器发布）。 */
    public int protectingServiceCount() {
        return protectingServices.get();
    }

    /** 节点级窗口统计（v0.1 语义，供 GetLeaseStatus 兼容与分区诊断）。 */
    public SelfProtectionStats nodeStats() {
        Window window = nodeWindow;
        return new SelfProtectionStats(window.currentRenewals(), window.maxCount.get(), window.isProtecting());
    }

    /** 测试钩子：清空全部窗口、状态与 gauge（计数器不重置）。 */
    public synchronized void reset() {
        for (Window window : windows.values()) {
            window.unregisterGauge();
        }
        windows.clear();
        protectingServices.set(0);
        nodeProtecting = false;
        nodeWindow.reset();
    }

    private static boolean matches(String value, String filter) {
        return isBlank(filter) || value.equals(filter);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum ProtectionState {
        NORMAL,
        PROTECTING
    }

    /**
     * 单个窗口：节点级（service = null）或服务级。写路径无锁；状态迁移只由 evaluate 触发。
     */
    private final class Window {

        private final ServiceKey service;
        private final AtomicLong maxCount = new AtomicLong();
        private volatile Buckets buckets;
        private volatile long maxCountUpdatedAtMs;
        private volatile ProtectionState state = ProtectionState.NORMAL;
        private volatile long protectingSinceMs;
        private volatile long lastWindowRenewals;
        /** 最近一次续约时刻（-1 = 尚无），用于窗口回收前的续约活动检查。 */
        private volatile long lastRenewalMs = -1;
        /** 缺席起始时刻（-1 = 未标记；单调钟原点可为 0，不能用作哨兵）。 */
        private volatile long absentSinceMs = -1;
        /** 最近一次评估使用的窗口长度：动态改变窗口口径时重置峰值基线，避免误判。 */
        private volatile int evaluatedWindowSeconds;

        private volatile Meter protectionGauge;

        private Window(ServiceKey service) {
            this.service = service;
            long now = clock.monoMillis();
            this.maxCountUpdatedAtMs = now;
            this.evaluatedWindowSeconds = Math.max(1, windowSeconds.getValue());
            this.buckets = new Buckets(evaluatedWindowSeconds, now / 1000);
        }

        private void add(long second, int count, long now) {
            int window = windowSeconds.getValue();
            Buckets current = bucketsFor(window);
            updatePeak(current.advance(second, window), now);
            if (second < current.lastSecond.get() - (window - 1L)) {
                return; // 迟到写者已跨轮：该桶已被后续秒复用，累加会污染未来窗口——直接丢弃
            }
            current.adders[current.index(second)].add(count);
            lastRenewalMs = now;
        }

        private void evaluate(
                long now,
                boolean node,
                boolean nodeProtectingNow,
                int window,
                int minCountValue,
                double factorValue,
                long resetMs) {
            long second = now / 1000;
            Buckets current = bucketsFor(window);
            updatePeak(current.advance(second, window), now);
            long renewals = current.sum(second, window);
            updatePeak(renewals, now);
            lastWindowRenewals = renewals;

            long peak = maxCount.get();
            boolean nextProtecting = peak >= minCountValue && renewals < peak * factorValue;
            if (window != evaluatedWindowSeconds) {
                // 窗口口径变化（运维动态调参）：以新窗口当前量重建基线，避免计数清零/缩短
                // 与旧峰值比较造成的误保护
                maxCount.set(renewals);
                maxCountUpdatedAtMs = now;
                evaluatedWindowSeconds = window;
                nextProtecting = false;
            } else if (node) {
                // 原产品语义：先按当前窗口判定；仅在当前安全时回落峰值。
                // 若在回落边界恰好骤降，必须先进入保护态，不能在判定前改写峰值。
                if (!nextProtecting && now - maxCountUpdatedAtMs >= resetMs) {
                    maxCount.set(renewals);
                    maxCountUpdatedAtMs = now;
                }
            } else if (!nodeProtectingNow && now - maxCountUpdatedAtMs >= resetMs) {
                // 服务窗口：节点健康时即使保护中也回落，限制客户端迁移造成的永久保护
                maxCount.set(renewals);
                maxCountUpdatedAtMs = now;
                nextProtecting = false;
            }
            transitionTo(nextProtecting, now, node);
        }

        /** 只读当前窗口续约量（不推进游标、不修改统计）：查询与指标抓取使用。 */
        private long currentRenewals() {
            Buckets current = buckets;
            int window = Math.min(windowSeconds.getValue(), current.adders.length);
            return current.sumCurrent(clock.monoMillis() / 1000, window);
        }

        private void transitionTo(boolean nextProtecting, long now, boolean node) {
            if (nextProtecting == isProtecting()) {
                return;
            }
            if (nextProtecting) {
                state = ProtectionState.PROTECTING;
                protectingSinceMs = now;
                if (node) {
                    log.warn(
                            "node self-protection entered: windowRenewals={}, maxCount={}",
                            lastWindowRenewals,
                            maxCount.get());
                } else {
                    registerGauge();
                    transitionsProtecting.increment();
                    log.warn(
                            "service self-protection entered: namespace={}, service={}, windowRenewals={}, maxCount={}",
                            service.namespace(),
                            service.service(),
                            lastWindowRenewals,
                            maxCount.get());
                }
            } else {
                state = ProtectionState.NORMAL;
                protectingSinceMs = 0;
                if (node) {
                    log.info(
                            "node self-protection exited: windowRenewals={}, maxCount={}",
                            lastWindowRenewals,
                            maxCount.get());
                } else {
                    unregisterGauge();
                    transitionsNormal.increment();
                    log.info(
                            "service self-protection exited: namespace={}, service={}, windowRenewals={}, maxCount={}",
                            service.namespace(),
                            service.service(),
                            lastWindowRenewals,
                            maxCount.get());
                }
            }
        }

        private void updatePeak(long observed, long now) {
            long previous = maxCount.get();
            while (observed > previous) {
                if (maxCount.compareAndSet(previous, observed)) {
                    maxCountUpdatedAtMs = now;
                    return;
                }
                previous = maxCount.get();
            }
        }

        private Buckets bucketsFor(int window) {
            Buckets current = buckets;
            if (window <= current.adders.length) {
                return current;
            }
            synchronized (this) {
                current = buckets;
                if (window > current.adders.length) {
                    // 动态放大窗口：重建环形数组，窗口计数清零、峰值保留（低频运维动作）
                    current = new Buckets(window, clock.monoMillis() / 1000);
                    buckets = current;
                }
                return current;
            }
        }

        private boolean isProtecting() {
            return state == ProtectionState.PROTECTING;
        }

        private void registerGauge() {
            if (protectionGauge != null) {
                return;
            }
            protectionGauge = Gauge.builder(METRIC_SERVICE_PROTECTION, this, window -> window.isProtecting() ? 1 : 0)
                    .description("service self-protection state (1 = protecting)")
                    .tag("namespace", service.namespace())
                    .tag("service", service.service())
                    .register(meterRegistry);
        }

        private void unregisterGauge() {
            Meter meter = protectionGauge;
            if (meter != null) {
                protectionGauge = null;
                meterRegistry.remove(meter);
            }
        }

        private void reset() {
            unregisterGauge();
            long now = clock.monoMillis();
            buckets = new Buckets(Math.max(1, windowSeconds.getValue()), now / 1000);
            maxCount.set(0);
            maxCountUpdatedAtMs = now;
            state = ProtectionState.NORMAL;
            protectingSinceMs = 0;
            lastWindowRenewals = 0;
            lastRenewalMs = -1;
            absentSinceMs = -1;
            evaluatedWindowSeconds = Math.max(1, windowSeconds.getValue());
        }
    }

    /** 1s 分桶环 + 自有游标；窗口动态放大时整体替换。 */
    private static final class Buckets {

        private final LongAdder[] adders;
        private final AtomicLong lastSecond;

        private Buckets(int capacity, long second) {
            this.adders = new LongAdder[capacity];
            for (int i = 0; i < capacity; i++) {
                adders[i] = new LongAdder();
            }
            this.lastSecond = new AtomicLong(second);
        }

        private int index(long second) {
            return (int) Math.floorMod(second, (long) adders.length);
        }

        private long sum(long second, int window) {
            long total = 0;
            for (long s = second - window + 1; s <= second; s++) {
                total += adders[index(s)].sum();
            }
            return total;
        }

        /**
         * 只读窗口求和：仅统计已推进到的秒（高于游标的桶可能残留上一轮数据，
         * 未推进的秒视为无续约），不修改游标或桶。
         */
        private long sumCurrent(long second, int window) {
            long last = Math.min(second, lastSecond.get());
            long start = Math.max(second - window + 1, last - window + 1);
            if (start > last) {
                return 0;
            }
            long total = 0;
            for (long s = start; s <= last; s++) {
                total += adders[index(s)].sum();
            }
            return total;
        }

        /**
         * 每秒推进并采样窗口峰值（跳跃推进时逐秒补采样，峰值在滑出前必被捕获），
         * 再清空换入秒的桶（跨窗口残留）。返回补采样过程中的最大窗口量。
         */
        private long advance(long second, int window) {
            long max = 0;
            long previous;
            while ((previous = lastSecond.get()) < second) {
                long next = previous + 1;
                if (lastSecond.compareAndSet(previous, next)) {
                    long renewals = sum(next - 1, window);
                    if (renewals > max) {
                        max = renewals;
                    }
                    adders[index(next)].reset();
                }
            }
            return max;
        }
    }
}
