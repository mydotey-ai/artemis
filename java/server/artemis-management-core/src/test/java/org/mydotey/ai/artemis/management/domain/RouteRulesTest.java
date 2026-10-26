package org.mydotey.ai.artemis.management.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 路由规则域约束矩阵（纯函数，内存/DB 两态共享语义，单测一处覆盖）。 */
class RouteRulesTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");

    @Test
    void should_pass_when_minimal_valid_rule() {
        assertThatCode(() -> RouteRules.validate(rule("beta", Map.of("group", "beta"), 20, 80)))
                .doesNotThrowAnyException();
    }

    @Test
    void should_allow_empty_selector_when_fallback_target() {
        assertThatCode(() -> RouteRules.validate(rule("fallback", Map.of(), 100, 0)))
                .doesNotThrowAnyException(); // 空 selector = 兜底目标（ADR-013 默认组语义承接）
    }

    @Test
    void should_reject_rule_when_identity_invalid() {
        // namespace/service/name 空白或超长
        assertThatThrownBy(() -> RouteRules.validate(rule(" ", Map.of(), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(new RouteRule(
                        new ServiceKey("a".repeat(256), "svc"),
                        "beta",
                        RouteRuleStatus.ACTIVE,
                        List.of(new RouteRuleTarget(Map.of(), 1, 0)),
                        null,
                        null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("a".repeat(256), Map.of(), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        // name 进 URL 路径段，限安全字符集
        assertThatThrownBy(() -> RouteRules.validate(rule("beta/1", Map.of(), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("贝 塔", Map.of(), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_rule_when_status_missing() {
        RouteRule rule =
                new RouteRule(SERVICE, "beta", null, List.of(new RouteRuleTarget(Map.of(), 1, 0)), null, null, null);
        assertThatThrownBy(() -> RouteRules.validate(rule)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_rule_when_target_count_out_of_bounds() {
        List<RouteRuleTarget> none = List.of();
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", none))).isInstanceOf(IllegalArgumentException.class);
        List<RouteRuleTarget> tooMany = List.copyOf(java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> new RouteRuleTarget(Map.of("g", "g" + i), 1, 0))
                .toList());
        assertThatThrownBy(() -> RouteRules.validate(
                        new RouteRule(SERVICE, "beta", RouteRuleStatus.ACTIVE, tooMany, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_rule_when_selector_invalid() {
        // 键数超 8
        Map<String, String> tooManyKeys = new java.util.HashMap<>();
        for (int i = 0; i < 9; i++) {
            tooManyKeys.put("k" + i, "v");
        }
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", tooManyKeys, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        // 键空白 / 超长；值空白 / 超长
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of(" ", "v"), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of("k".repeat(129), "v"), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of("k", " "), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of("k", "v".repeat(513)), 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_rule_when_weight_out_of_range() {
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of(), -1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteRules.validate(rule("beta", Map.of(), 0, 10_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RouteRule rule(String name, Map<String, String> selector, int weight, int unreleased) {
        return new RouteRule(
                SERVICE,
                name,
                RouteRuleStatus.ACTIVE,
                List.of(new RouteRuleTarget(selector, weight, unreleased)),
                null,
                null,
                null);
    }

    private static RouteRule rule(String name, List<RouteRuleTarget> targets) {
        return new RouteRule(SERVICE, name, RouteRuleStatus.ACTIVE, targets, null, null, null);
    }
}
