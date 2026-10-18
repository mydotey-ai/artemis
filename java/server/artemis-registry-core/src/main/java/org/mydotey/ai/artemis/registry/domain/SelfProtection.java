package org.mydotey.ai.artemis.registry.domain;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 续约滑动窗口统计与保护态判定（资产 #10 继承；按服务粒度归 v0.2 自我保护 2.0）。
 * 线程安全、全方法非阻塞。
 *
 * <p>窗口实现：按秒分桶环形数组（桶数 = window/1s）+ 每桶 LongAdder；写入按单调秒落桶，
 * 桶轮换经 CAS 游标由单个推进者清空跨窗口残留（并发推进失败者直接累加，轮换清扫幂等）；
 * 读取聚合当前窗口内秒桶。写入路径零锁——心跳高频路径不给保护统计付锁开销。
 *
 * <p>判定语义（对齐原产品 LeaseUpdateSafeChecker，legacy §2.3）：maxCount = 启动以来窗口
 * 续约量的历史最大值（只增不减）；未达 minCount 不启用判定；当前窗口量 &lt; maxCount × factor
 * 进入保护态。
 *
 * <p>时间源（D14）：窗口推进取 {@link MonotonicClock}（时长判定，单调不受校时影响）。
 */
public final class SelfProtection {

    private final SelfProtectionConfig config;
    private final MonotonicClock clock;
    private final LongAdder[] buckets;
    private final AtomicLong lastSecond;
    private final AtomicLong maxCount = new AtomicLong();

    public SelfProtection(SelfProtectionConfig config, MonotonicClock clock) {
        long bucketSeconds = config.window().toSeconds();
        if (bucketSeconds < 1) {
            throw new IllegalArgumentException("self-protection window must be >= 1s: " + config.window());
        }
        this.config = config;
        this.clock = clock;
        this.buckets = new LongAdder[(int) bucketSeconds];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        // 构造即对齐当前秒：游标从 0 起会在首个调用时逐秒追赶 O(主机 uptime)（单调钟原点任意），
        // 长 uptime 主机上阻塞首个心跳/清理线程达秒级
        this.lastSecond = new AtomicLong(clock.monoMillis() / 1000);
    }

    /**
     * 记录一次心跳的续约量（请求数聚合后单次累加；显式 register/update/deregister 与
     * 复制事件不计——非续约信号）。
     */
    public void recordRenewals(int instanceCount) {
        if (instanceCount <= 0) {
            return;
        }
        long second = clock.monoMillis() / 1000;
        advanceBuckets(second);
        if (second < lastSecond.get() - (buckets.length - 1)) {
            return; // 迟到写者已跨轮：该桶已被后续秒复用，累加会污染未来窗口——直接丢弃（统计语义容忍）
        }
        buckets[index(second)].add(instanceCount);
    }

    /** 测试钩子：清空窗口计数与历史峰值（单调钟基准保持不变）。 */
    public void reset() {
        lastSecond.set(clock.monoMillis() / 1000);
        for (LongAdder bucket : buckets) {
            bucket.reset();
        }
        maxCount.set(0);
    }

    /** 保护态判定：maxCount ≥ minCount（启用下限）且当前窗口续约量 &lt; maxCount × factor。 */
    public boolean isProtected() {
        return stats().protecting();
    }

    /** 窗口统计快照（当前窗口量、历史峰值、是否保护）。 */
    public SelfProtectionStats stats() {
        long second = clock.monoMillis() / 1000;
        advanceBuckets(second); // 读取侧同样惰性推进：无写入的静默期过后，残留旧计数须先出桶再聚合
        long windowRenewals = sumWindow(second);
        long peak = maxCount.accumulateAndGet(windowRenewals, Math::max);
        boolean protecting = peak >= config.minCount() && windowRenewals < peak * config.factor();
        return new SelfProtectionStats(windowRenewals, peak, protecting);
    }

    /**
     * 秒轮换推进（CAS 游标逐秒步进，多推进者竞争）：每步先以「步进前一秒」为窗口右端采样
     * （跳跃推进时逐秒补采样——窗口峰值在数据滑出前必被捕获，maxCount 只增不减不靠查询驱动），
     * 再清空换入秒的桶（跨窗口残留）。正常写线程的累加发生在其秒桶本轮换清理之后；
     * 跨轮迟到的写者由 {@link #recordRenewals} 丢弃。并发采样弱一致（LongAdder 快照），统计语义容忍。
     */
    private void advanceBuckets(long second) {
        long prev;
        while ((prev = lastSecond.get()) < second) {
            long next = prev + 1;
            if (lastSecond.compareAndSet(prev, next)) {
                maxCount.accumulateAndGet(sumWindow(next - 1), Math::max);
                buckets[index(next)].reset();
            }
        }
    }

    private long sumWindow(long second) {
        long total = 0;
        for (long s = second - buckets.length + 1; s <= second; s++) {
            total += buckets[index(s)].sum();
        }
        return total;
    }

    /** 环形索引：floorMod 兼容负单调秒（System.nanoTime() 原点任意）。 */
    private int index(long second) {
        return (int) Math.floorMod(second, (long) buckets.length);
    }
}
