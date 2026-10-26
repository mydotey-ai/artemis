package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 路由规则集（phase-2）：快照替换 + 增量幂等集合操作（等号/旧号事件安全重放）。 */
class RouteRulesTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    @Test
    void should_build_from_snapshot_with_replacement_semantics() {
        RouteRules rules = RouteRules.of(List.of(rule(ORDER, "beta"), rule(PAY, "beta")));

        assertThat(rules.size()).isEqualTo(2);
        assertThat(rules.find(ORDER, "beta")).isPresent();
        assertThat(rules.find(ORDER, "missing")).isEmpty();
        // 空快照 = EMPTY（替换语义含空集）
        assertThat(RouteRules.of(List.of())).isSameAs(RouteRules.EMPTY);
    }

    @Test
    void should_upsert_and_delete_idempotently_in_route_rules() {
        RouteRules rules = RouteRules.of(List.of(rule(ORDER, "beta")));
        RouteRule updated = rule(ORDER, "beta"); // 键等值同内容

        assertThat(rules.with(updated)).isSameAs(rules); // 幂等：同内容返回自身

        RouteRules changed = rules.with(rule(ORDER, "v2"));
        assertThat(changed.size()).isEqualTo(2); // 键不同并存

        // 同键覆盖：同名新内容替换旧内容，规模不变
        RouteRules replaced = changed.with(new RouteRule(ORDER, "beta", List.of()));
        assertThat(replaced.size()).isEqualTo(2);
        assertThat(replaced.find(ORDER, "beta")).contains(new RouteRule(ORDER, "beta", List.of()));

        RouteRuleKey missing = new RouteRuleKey(PAY, "beta");
        assertThat(changed.without(missing)).isSameAs(changed); // 未存在幂等

        RouteRules removed = changed.without(new RouteRuleKey(ORDER, "v2"));
        assertThat(removed.find(ORDER, "beta")).isPresent(); // 其余保留
        assertThat(removed.without(new RouteRuleKey(ORDER, "beta"))).isSameAs(RouteRules.EMPTY); // 清空回落
    }

    @Test
    void should_list_in_stable_order() {
        RouteRules rules = RouteRules.of(List.of(rule(PAY, "z"), rule(ORDER, "b"), rule(ORDER, "a")));

        assertThat(rules.list())
                .extracting(rule -> rule.service().service(), RouteRule::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("order-service", "a"),
                        org.assertj.core.groups.Tuple.tuple("order-service", "b"),
                        org.assertj.core.groups.Tuple.tuple("pay-service", "z"));
    }

    @Test
    void should_route_visibility_by_selector_and_weight() {
        // beta 组 weight 0（摘出）+ 兜底 weight 70
        RouteRules rules = RouteRules.of(List.of(new RouteRule(
                ORDER,
                "release",
                List.of(
                        new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 0, 30),
                        new RouteRule.RouteRuleTarget(Map.of(), 70, 70)))));

        assertThat(rules.isVisible(ORDER, Map.of("group", "beta"))).isFalse(); // 命中 0 权重目标 → 摘出
        assertThat(rules.isVisible(ORDER, Map.of("group", "stable"))).isTrue(); // 未命中 → 兜底承接
        assertThat(rules.isVisible(ORDER, Map.of())).isTrue(); // 无 metadata 约定 → 兜底承接
        assertThat(rules.isVisible(PAY, Map.of("group", "beta"))).isTrue(); // 服务无规则 → 全可见
        assertThat(rules.services()).containsExactly(ORDER);

        // 命中 0 权重目标不归兜底（兜底 > 0 仍摘出）；多键 AND：单键命中不匹配
        RouteRules twoKeys = RouteRules.of(List.of(new RouteRule(
                ORDER,
                "zone",
                List.of(new RouteRule.RouteRuleTarget(Map.of("group", "beta", "zone", "dc-1"), 100, 100)))));
        assertThat(twoKeys.isVisible(ORDER, Map.of("group", "beta"))).isFalse();
        assertThat(twoKeys.isVisible(ORDER, Map.of("group", "beta", "zone", "dc-1")))
                .isTrue();

        // 无兜底：未命中任何 selector 的实例无承接目标 → 不可见
        RouteRules noFallback = RouteRules.of(List.of(new RouteRule(
                ORDER, "only", List.of(new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 100, 100)))));
        assertThat(noFallback.isVisible(ORDER, Map.of("group", "stable"))).isFalse();
        assertThat(noFallback.isVisible(ORDER, Map.of("group", "beta"))).isTrue();
    }

    @Test
    void should_combine_multiple_rules_with_and() {
        // r1 只放 beta 组、r2 只放 dc-1：实例须同时满足两条才可见
        RouteRules rules = RouteRules.of(List.of(
                new RouteRule(
                        ORDER, "group", List.of(new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 100, 100))),
                new RouteRule(
                        ORDER, "zone", List.of(new RouteRule.RouteRuleTarget(Map.of("zone", "dc-1"), 100, 100)))));

        assertThat(rules.isVisible(ORDER, Map.of("group", "beta", "zone", "dc-1")))
                .isTrue();
        assertThat(rules.isVisible(ORDER, Map.of("group", "beta", "zone", "dc-2")))
                .isFalse();
        assertThat(rules.rules(ORDER)).hasSize(2);
    }

    @Test
    void should_reflect_rule_updates_in_group_filter() {
        GroupFilter filter = new GroupFilter();
        Instance beta = new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("group", "beta"));
        Instance stable = new Instance("i2", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("group", "stable"));

        assertThat(filter.isVisible(ORDER, beta)).isTrue(); // 无规则全可见

        filter.replace(RouteRules.of(List.of(new RouteRule(
                ORDER,
                "release",
                List.of(
                        new RouteRule.RouteRuleTarget(Map.of("group", "beta"), 0, 30),
                        new RouteRule.RouteRuleTarget(Map.of(), 70, 70))))));
        assertThat(filter.isVisible(ORDER, beta)).isFalse();
        assertThat(filter.isVisible(ORDER, stable)).isTrue();

        filter.replace(RouteRules.EMPTY);
        assertThat(filter.isVisible(ORDER, beta)).isTrue(); // 空快照回归全可见
    }

    @Test
    void should_tolerate_malformed_rule_as_no_governance() {
        // 空 targets（management 校验 1–10 条但 wire 不设防）：视为无治理意图全可见，
        // 规则数据损坏不得静默摘除整个服务（review #1 纵深防御）
        RouteRules emptyTargets = RouteRules.of(List.of(new RouteRule(ORDER, "broken", List.of())));
        assertThat(emptyTargets.isVisible(ORDER, Map.of("group", "beta"))).isTrue();

        // 多兜底目标（违反「至多一条」等值约束）：首个生效（review #2 确定性降级）——
        // 首个 w0、次个 w70 → 按首个判定，未命中实例摘出
        RouteRules twoFallbacks = RouteRules.of(List.of(new RouteRule(
                ORDER,
                "dup",
                List.of(
                        new RouteRule.RouteRuleTarget(Map.of(), 0, 0),
                        new RouteRule.RouteRuleTarget(Map.of(), 70, 70)))));
        assertThat(twoFallbacks.isVisible(ORDER, Map.of())).isFalse();
    }

    private static RouteRule rule(ServiceKey service, String name) {
        return new RouteRule(service, name, List.of(new RouteRule.RouteRuleTarget(Map.of(), 100, 100)));
    }
}
