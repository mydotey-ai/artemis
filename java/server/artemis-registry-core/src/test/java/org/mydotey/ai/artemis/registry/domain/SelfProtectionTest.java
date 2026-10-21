package org.mydotey.ai.artemis.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;

/**
 * 自我保护 2.0：节点级兜底 + 服务级双层窗口、显式状态机、回落策略、有界指标与窗口回收。
 */
class SelfProtectionTest {

    private static final ServiceKey A = new ServiceKey("ns", "svc-a");
    private static final ServiceKey B = new ServiceKey("ns", "svc-b");
    private static final ServiceKey C = new ServiceKey("other", "svc-c");

    private MutableClock clock;
    private SimpleMeterRegistry meters;
    private TestProperties properties;
    private SelfProtection protection;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        meters = new SimpleMeterRegistry();
        properties = TestProperties.of(Map.of(
                SelfProtection.KEY_WINDOW_SECONDS, "10",
                SelfProtection.KEY_MIN_COUNT, "5",
                SelfProtection.KEY_FACTOR, "0.85",
                SelfProtection.KEY_MAX_COUNT_RESET_MS, "60000"));
        protection = new SelfProtection(properties.properties(), meters, clock);
    }

    @Test
    void should_isolate_service_windows_when_one_service_renewals_drop() {
        protection.recordRenewals(A, 100);
        protection.recordRenewals(B, 100);
        evaluate(A, B);

        clock.advance(Duration.ofSeconds(11));
        protection.recordRenewals(B, 90);
        evaluate(A, B);

        assertThat(protection.isProtected(A)).isTrue();
        assertThat(protection.isProtected(B)).isFalse();
        assertThat(status(A).protecting()).isTrue();
        assertThat(status(B).protecting()).isFalse();
    }

    @Test
    void should_keep_max_count_per_service_when_windows_differ() {
        protection.recordRenewals(A, 100);
        protection.recordRenewals(B, 50);
        evaluate(A, B);

        clock.advance(Duration.ofSeconds(11));
        evaluate(A, B);

        assertThat(status(A).maxCount()).isEqualTo(100);
        assertThat(status(B).maxCount()).isEqualTo(50);
    }

    @Test
    void should_fall_back_to_node_protection_when_service_below_min_count() {
        protection.recordRenewals(B, 100);
        protection.recordRenewals(A, 4);
        evaluate(A, B);

        clock.advance(Duration.ofSeconds(11));
        evaluate(A, B);

        // A 峰值 4 < minCount 5：服务级无资格，节点级保护中 → 受保护
        assertThat(protection.isProtected(A)).isTrue();
        assertThat(status(A).maxCount()).isEqualTo(4);
        assertThat(status(A).nodeFallback()).isTrue();
    }

    @Test
    void should_fall_back_to_node_protection_when_service_has_no_window() {
        protection.recordRenewals(B, 100);
        evaluate(B);

        clock.advance(Duration.ofSeconds(11));
        evaluate(B);

        assertThat(protection.isProtected(A)).isTrue();
    }

    @Test
    void should_use_service_state_when_node_protects_but_service_healthy() {
        protection.recordRenewals(A, 100);
        protection.recordRenewals(B, 90);
        evaluate(A, B);

        clock.advance(Duration.ofSeconds(11));
        protection.recordRenewals(B, 90);
        evaluate(A, B);

        // 节点级保护中（总续约从 190 跌到 90），但 B 服务级续约正常 → 服务级权威
        assertThat(protection.isProtected(A)).isTrue();
        assertThat(protection.isProtected(B)).isFalse();
        assertThat(status(B).nodeFallback()).isFalse();
    }

    @Test
    void should_enter_and_exit_protecting_state_with_since_duration() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);

        assertThat(status(A).protecting()).isTrue();
        assertThat(status(A).protectingForMs()).isZero();

        clock.advance(Duration.ofSeconds(5));
        evaluate(A);
        assertThat(status(A).protectingForMs()).isEqualTo(5_000);

        protection.recordRenewals(A, 90);
        evaluate(A);
        assertThat(status(A).protecting()).isFalse();
        assertThat(status(A).protectingForMs()).isZero();
    }

    @Test
    void should_reset_service_max_count_when_node_healthy_after_reset_interval() {
        protection.recordRenewals(A, 100);
        protection.recordRenewals(B, 1000);
        evaluate(A, B);

        // 节点保持健康（B 续约量占绝对多数），A 续约归零 → A 保护
        clock.advance(Duration.ofSeconds(11));
        protection.recordRenewals(B, 1000);
        evaluate(A, B);
        assertThat(protection.isProtected(A)).isTrue();

        // 节点健康 + 峰值超过 maxCountResetMs 未抬升 → A 回落并退出保护
        clock.advance(Duration.ofSeconds(60));
        protection.recordRenewals(B, 1000);
        evaluate(A, B);

        assertThat(protection.isProtected(A)).isFalse();
        assertThat(status(A).maxCount()).isZero();
        assertThat(status(A).windowRenewals()).isZero();
    }

    @Test
    void should_not_reset_service_max_count_when_node_protecting() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();

        clock.advance(Duration.ofSeconds(61));
        evaluate(A);

        // 节点级保护中（全节点续约归零）→ 服务窗口不回落，保护持续
        assertThat(protection.isProtected(A)).isTrue();
        assertThat(status(A).maxCount()).isEqualTo(100);
        assertThat(status(A).protectingForMs()).isGreaterThanOrEqualTo(61_000);
    }

    @Test
    void should_reset_node_max_count_only_when_safe() {
        protection.recordRenewals(B, 1000);
        evaluate(B);

        clock.advance(Duration.ofSeconds(11));
        protection.recordRenewals(B, 900);
        evaluate(B);
        assertThat(protection.nodeStats().maxCount()).isEqualTo(1000);

        clock.advance(Duration.ofSeconds(60));
        protection.recordRenewals(B, 900);
        evaluate(B);

        // 安全态（90%）且 60s 未抬升 → 节点峰值回落到当前量
        assertThat(protection.nodeStats().maxCount()).isEqualTo(900);
        assertThat(protection.nodeStats().protecting()).isFalse();
    }

    @Test
    void should_not_reset_node_peak_before_protection_when_drop_at_reset_boundary() {
        protection.recordRenewals(A, 1000);
        evaluate(A);
        assertThat(protection.nodeStats().maxCount()).isEqualTo(1000);

        // 峰值 60s 未抬升，恰在回落检查时续约骤降至 0：必须先判定保护，不能先改写峰值
        clock.advance(Duration.ofSeconds(61));
        evaluate(A);

        assertThat(protection.nodeStats().protecting()).isTrue();
        assertThat(protection.nodeStats().maxCount()).isEqualTo(1000);
        assertThat(protection.isProtected(A)).isTrue();
    }

    @Test
    void should_report_current_window_renewals_between_evaluations() {
        protection.recordRenewals(A, 100);
        assertThat(status(A).windowRenewals()).isEqualTo(100);

        // 不经过 evaluate，查询仍按当前窗口只读求和
        clock.advance(Duration.ofSeconds(5));
        protection.recordRenewals(A, 20);

        assertThat(status(A).windowRenewals()).isEqualTo(120);
        assertThat(protection.nodeStats().windowRenewals()).isEqualTo(120);
    }

    @Test
    void should_keep_window_when_renewal_arrives_after_absent_mark() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(2));
        evaluate(); // 标记缺席

        clock.advance(Duration.ofMillis(100));
        protection.recordRenewals(A, 10); // 并发心跳晚于缺席标记
        clock.advance(Duration.ofMillis(400));
        evaluate();
        assertThat(protection.trackedServiceCount()).isEqualTo(1); // 按最后续约重新计时

        clock.advance(Duration.ofSeconds(2));
        evaluate();
        assertThat(protection.trackedServiceCount()).isZero();
    }

    @Test
    void should_reenter_protection_when_renewals_drop_again_after_recovery() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();

        protection.recordRenewals(A, 90);
        evaluate(A);
        assertThat(protection.isProtected(A)).isFalse();

        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();
    }

    @Test
    void should_slide_window_per_service_when_renewals_age_out() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(5));
        protection.recordRenewals(A, 20);
        evaluate(A);

        assertThat(status(A).windowRenewals()).isEqualTo(120);

        clock.advance(Duration.ofSeconds(11));
        evaluate(A);

        assertThat(status(A).windowRenewals()).isZero();
        assertThat(status(A).maxCount()).isEqualTo(120);
    }

    @Test
    void should_not_protect_when_maxcount_below_min() {
        protection.recordRenewals(A, 4);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);

        assertThat(protection.isProtected(A)).isFalse();
        assertThat(protection.nodeStats().protecting()).isFalse();
    }

    @Test
    void should_use_latest_thresholds_when_changed() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();

        properties.set(SelfProtection.KEY_FACTOR, "0.5");
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();

        properties.set(SelfProtection.KEY_MIN_COUNT, "101");
        evaluate(A);
        assertThat(protection.isProtected(A)).isFalse();
    }

    @Test
    void should_rebase_peak_when_window_changed() {
        protection.recordRenewals(A, 100); // 第 0 秒
        evaluate(A);
        clock.advance(Duration.ofSeconds(6));
        evaluate(A);
        assertThat(status(A).windowRenewals()).isEqualTo(100);

        // 缩短窗口：当前窗口量变小，但不与新口径的比较产生保护误判
        properties.set(SelfProtection.KEY_WINDOW_SECONDS, "5");
        evaluate(A);
        assertThat(status(A).windowRenewals()).isZero();
        assertThat(status(A).maxCount()).isZero();
        assertThat(protection.isProtected(A)).isFalse();

        // 放大窗口：环形数组重建；以新窗口当前量重建基线，不保留旧口径峰值
        properties.set(SelfProtection.KEY_WINDOW_SECONDS, "20");
        protection.recordRenewals(A, 10);
        evaluate(A);
        assertThat(status(A).windowRenewals()).isEqualTo(10);
        assertThat(status(A).maxCount()).isEqualTo(10);
        assertThat(protection.isProtected(A)).isFalse();
    }

    @Test
    void should_register_service_protection_gauge_only_while_protecting() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);

        assertThat(meters.find("artemis.registry.self.service.protection")
                        .tag("namespace", "ns")
                        .tag("service", "svc-a")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("artemis.registry.self.service.protection.count")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("artemis.registry.self.service.protection.transitions")
                        .tag("to", "protecting")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        protection.recordRenewals(A, 90);
        evaluate(A);

        assertThat(meters.find("artemis.registry.self.service.protection")
                        .tag("namespace", "ns")
                        .tag("service", "svc-a")
                        .gauge())
                .isNull();
        assertThat(meters.get("artemis.registry.self.service.protection.count")
                        .gauge()
                        .value())
                .isZero();
        assertThat(meters.get("artemis.registry.self.service.protection.transitions")
                        .tag("to", "normal")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void should_report_node_gauges_with_v01_names() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);

        assertThat(meters.get("artemis.registry.self.protection").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("artemis.registry.self.renewals").gauge().value()).isZero();
        assertThat(meters.get("artemis.registry.self.max").gauge().value()).isEqualTo(100.0);
    }

    @Test
    void should_remove_gauge_when_service_window_reclaimed() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.trackedServiceCount()).isEqualTo(1);

        evaluate(); // 第一次发现消失
        clock.advance(Duration.ofSeconds(2));
        evaluate(); // 超过清理宽限 → 回收

        assertThat(protection.trackedServiceCount()).isZero();
        assertThat(protection.statuses(null, null, true)).isEmpty();
        assertThat(meters.find("artemis.registry.self.service.protection")
                        .tag("namespace", "ns")
                        .tag("service", "svc-a")
                        .gauge())
                .isNull();
    }

    @Test
    void should_keep_window_when_service_still_live() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(2));
        evaluate(A);

        assertThat(protection.trackedServiceCount()).isEqualTo(1);
        assertThat(status(A).maxCount()).isEqualTo(100);
    }

    @Test
    void should_report_statuses_sorted_and_filtered() {
        protection.recordRenewals(C, 100);
        protection.recordRenewals(A, 100);
        evaluate(A, C);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A, C);

        assertThat(protection.statuses(null, null, false))
                .extracting(status -> status.service().service())
                .containsExactly("svc-a", "svc-c");
        assertThat(protection.statuses("ns", null, false)).hasSize(1);
        assertThat(protection.statuses(null, "svc-c", false))
                .singleElement()
                .extracting(status -> status.service().namespace())
                .isEqualTo("other");
    }

    @Test
    void should_return_synthetic_status_when_filtered_service_has_no_window() {
        protection.recordRenewals(B, 100);
        evaluate(B);
        clock.advance(Duration.ofSeconds(11));
        evaluate(B);
        assertThat(protection.nodeStats().protecting()).isTrue();

        // 指定 namespace + service：无本地窗口也返回零计数条目（节点级兜底）
        assertThat(protection.statuses("ns", "ghost", false)).singleElement().satisfies(item -> {
            assertThat(item.service()).isEqualTo(new ServiceKey("ns", "ghost"));
            assertThat(item.windowRenewals()).isZero();
            assertThat(item.maxCount()).isZero();
            assertThat(item.nodeFallback()).isTrue();
            assertThat(item.protecting()).isFalse();
        });

        // 仅 service 无 namespace：无法合成 key，返回空
        assertThat(protection.statuses(null, "ghost", false)).isEmpty();
    }

    @Test
    void should_reset_all_windows_for_test_isolation() {
        protection.recordRenewals(A, 100);
        evaluate(A);
        clock.advance(Duration.ofSeconds(11));
        evaluate(A);
        assertThat(protection.isProtected(A)).isTrue();

        protection.reset();

        assertThat(protection.trackedServiceCount()).isZero();
        assertThat(protection.protectingServiceCount()).isZero();
        assertThat(protection.nodeStats()).isEqualTo(new SelfProtectionStats(0, 0, false));
        assertThat(protection.isProtected(A)).isFalse();
    }

    @Test
    void should_handle_negative_monotonic_seconds() {
        clock.advance(Duration.ofSeconds(-100));
        SelfProtection negativeOrigin = new SelfProtection(properties.properties(), meters, clock);

        negativeOrigin.recordRenewals(A, 100);
        negativeOrigin.evaluate(Set.of(A));

        assertThat(negativeOrigin.statuses("ns", "svc-a", true))
                .singleElement()
                .extracting(ServiceProtectionStatus::windowRenewals)
                .isEqualTo(100L);
    }

    @Test
    void should_not_catch_up_from_epoch_when_clock_origin_is_large() {
        clock.advance(Duration.ofDays(30));
        SelfProtection longUptime = new SelfProtection(properties.properties(), meters, clock);

        long started = System.nanoTime();
        longUptime.recordRenewals(A, 100);
        longUptime.evaluate(Set.of(A));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void should_use_defaults_when_properties_empty() {
        SelfProtection defaults = new SelfProtection(TestProperties.empty(), meters, clock);
        defaults.recordRenewals(A, 100);
        clock.advance(Duration.ofSeconds(11));
        defaults.evaluate(Set.of(A));

        // 默认 10s 窗口 / minCount 50 / factor 0.85：峰值 100 ≥ 50，窗口 0 < 85 → 保护
        assertThat(defaults.isProtected(A)).isTrue();
    }

    @Test
    void should_fall_back_to_default_factor_when_out_of_range() {
        SelfProtection invalid = new SelfProtection(
                TestProperties.of(Map.of(SelfProtection.KEY_FACTOR, "2.0")).properties(), meters, clock);
        invalid.recordRenewals(A, 100);
        invalid.evaluate(Set.of(A));
        clock.advance(Duration.ofSeconds(11));
        invalid.evaluate(Set.of(A));
        invalid.recordRenewals(A, 90);
        invalid.evaluate(Set.of(A));

        // 越界 factor 2.0 回落默认 0.85：90 不低于 100×0.85 → 不保护
        assertThat(invalid.isProtected(A)).isFalse();
    }

    private void evaluate(ServiceKey... live) {
        protection.evaluate(Set.of(live));
    }

    private ServiceProtectionStatus status(ServiceKey key) {
        return protection.statuses(key.namespace(), key.service(), true).stream()
                .findFirst()
                .orElseThrow();
    }
}
