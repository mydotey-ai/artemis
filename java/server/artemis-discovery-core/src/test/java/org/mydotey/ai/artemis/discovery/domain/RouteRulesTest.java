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

    private static RouteRule rule(ServiceKey service, String name) {
        return new RouteRule(service, name, List.of(new RouteRule.RouteRuleTarget(Map.of(), 100, 100)));
    }
}
