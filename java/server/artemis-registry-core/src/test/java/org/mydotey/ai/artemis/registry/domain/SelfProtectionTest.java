package org.mydotey.ai.artemis.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;

/** 自我保护：窗口统计（分桶滑动、maxCount 只增不减）与阈值判定（minCount 下限、factor 阈值）。 */
class SelfProtectionTest {

    private MutableClock clock;
    private SimpleMeterRegistry meters;
    private TestProperties properties;
    private SelfProtection protection;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        meters = new SimpleMeterRegistry();
        // 小参数：10 桶窗口、0.85 因子、minCount 5——测试无需生产规模心跳量
        properties = TestProperties.of(Map.of(
                SelfProtection.KEY_WINDOW_SECONDS, "10",
                SelfProtection.KEY_MIN_COUNT, "5",
                SelfProtection.KEY_FACTOR, "0.85"));
        protection = new SelfProtection(properties.properties(), meters, clock);
    }

    @Test
    void should_not_protect_when_maxcount_below_min() {
        // 峰值 4 < minCount 5：即使窗口滑空（续约量跌到 0）也不进保护态（小规模集群不误保护）
        protection.recordRenewals(4);
        clock.advance(Duration.ofSeconds(11));

        assertThat(protection.stats().maxCount()).isEqualTo(4);
        assertThat(protection.isProtected()).isFalse();
    }

    @Test
    void should_enter_protection_when_window_renewals_below_threshold() {
        // 峰值 100（≥5）后心跳骤停：窗口滑出后当前量 0 < 100×0.85 → 保护
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));

        assertThat(protection.stats().windowRenewals()).isZero();
        assertThat(protection.stats().maxCount()).isEqualTo(100); // 只增不减
        assertThat(protection.isProtected()).isTrue();
    }

    @Test
    void should_exit_protection_when_renewals_recover() {
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.isProtected()).isTrue();

        // 续约恢复到阈值之上（≥ 85）退出保护
        protection.recordRenewals(90);

        assertThat(protection.stats().windowRenewals()).isEqualTo(90);
        assertThat(protection.isProtected()).isFalse();
    }

    @Test
    void should_slide_window_by_dropping_expired_buckets() {
        protection.recordRenewals(100); // 秒 0 落桶 0

        // 秒 5：仍在窗口内（窗口 [0..10] 聚合）
        clock.advance(Duration.ofSeconds(5));
        assertThat(protection.stats().windowRenewals()).isEqualTo(100);

        // 秒 8 再记 20：窗口 [0..8] 聚合 100+20
        protection.recordRenewals(20);
        assertThat(protection.stats().windowRenewals()).isEqualTo(120);

        // 秒 19：窗口 [10..19]，秒 0 的 100 已出窗、秒 8 的 20 已出窗（8 < 10）
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.stats().windowRenewals()).isZero();

        // maxCount 保留历史峰值（只增不减）
        assertThat(protection.stats().maxCount()).isEqualTo(120);
    }

    @Test
    void should_not_catch_up_from_epoch_when_clock_origin_is_large() {
        // 生产单调钟以主机开机为原点：构造即对齐，首调不得逐秒追赶 O(uptime)（30 天原实现实测 ~0.4s）
        clock.advance(Duration.ofDays(30));
        SelfProtection longUptime = new SelfProtection(properties.properties(), meters, clock);

        long started = System.nanoTime();
        longUptime.recordRenewals(100);

        assertThat(longUptime.stats().windowRenewals()).isEqualTo(100);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void should_handle_negative_monotonic_seconds() {
        // 单调钟原点任意（System.nanoTime() 规范允许负值）：环形索引须用 floorMod
        clock.advance(Duration.ofSeconds(-100));
        SelfProtection negativeOrigin = new SelfProtection(properties.properties(), meters, clock);

        negativeOrigin.recordRenewals(100);

        assertThat(negativeOrigin.stats().windowRenewals()).isEqualTo(100);
    }

    @Test
    void should_use_defaults_when_properties_empty() {
        SelfProtection defaults = new SelfProtection(TestProperties.empty(), meters, clock);
        defaults.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));

        // 默认 10s 窗口 / minCount 50 / factor 0.85：峰值 100 ≥ 50，窗口 0 < 85 → 保护
        assertThat(defaults.isProtected()).isTrue();
    }

    @Test
    void should_fall_back_to_default_factor_when_out_of_range() {
        SelfProtection invalid = new SelfProtection(
                TestProperties.of(Map.of(SelfProtection.KEY_FACTOR, "2.0")).properties(), meters, clock);
        invalid.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        invalid.recordRenewals(90);

        // 越界 factor 2.0 回落默认 0.85：90 < 100×0.85 为假 → 不保护（若误用 2.0 则保护）
        assertThat(invalid.isProtected()).isFalse();
    }

    @Test
    void should_use_latest_thresholds_when_changed() {
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.isProtected()).isTrue();

        // factor 动态下调：当前量 0 < peak × 0.5 仍保护
        properties.set(SelfProtection.KEY_FACTOR, "0.5");
        assertThat(protection.isProtected()).isTrue();

        // minCount 动态上调到峰值之上：不再启用判定
        properties.set(SelfProtection.KEY_MIN_COUNT, "101");
        assertThat(protection.isProtected()).isFalse();
    }

    @Test
    void should_register_protection_gauges_when_constructed() {
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));

        assertThat(meters.get("artemis.registry.self.protection").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("artemis.registry.self.renewals").gauge().value()).isZero();
        assertThat(meters.get("artemis.registry.self.max").gauge().value()).isEqualTo(100.0);
    }

    @Test
    void should_reset_statistics_for_test_isolation() {
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.isProtected()).isTrue();

        protection.reset();

        assertThat(protection.stats().windowRenewals()).isZero();
        assertThat(protection.stats().maxCount()).isZero();
        assertThat(protection.isProtected()).isFalse();
    }
}
