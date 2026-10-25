package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;

class EjectionRulesTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    @Test
    void should_apply_snapshot_as_replacement() {
        EjectionRules rules = EjectionRules.of(List.of(rule(ORDER, "i1"), rule(PAY, "p1")));
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isTrue();
        assertThat(rules.isEjected(ORDER, "i2", Map.of())).isFalse();
        assertThat(rules.isEmpty()).isFalse();

        // 快照替换：旧规则整体失效
        rules = EjectionRules.of(List.of(rule(ORDER, "i9")));
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isFalse();
        assertThat(rules.isEjected(PAY, "p1", Map.of())).isFalse();
        assertThat(rules.isEjected(ORDER, "i9", Map.of())).isTrue();
    }

    @Test
    void should_apply_delta_with_and_without() {
        EjectionRules rules = EjectionRules.EMPTY;

        rules = rules.with(rule(ORDER, "i1"));
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isTrue();

        // 幂等：已存在返回自身
        assertThat(rules.with(rule(ORDER, "i1"))).isSameAs(rules);

        rules = rules.without(EjectionTarget.of(rule(ORDER, "i1")));
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isFalse();

        // 幂等：未摘除返回自身
        assertThat(rules.without(EjectionTarget.of(rule(ORDER, "i1")))).isSameAs(rules);
    }

    @Test
    void should_enumerate_services_and_instances_for_diff() {
        EjectionRules rules = EjectionRules.of(List.of(rule(ORDER, "i1"), rule(ORDER, "i2"), rule(PAY, "p1")));

        assertThat(rules.services()).containsExactlyInAnyOrder(ORDER, PAY);
        assertThat(rules.instances(ORDER)).containsExactlyInAnyOrder("i1", "i2");
        assertThat(rules.instances(PAY)).containsExactly("p1");
        assertThat(rules.instances(new ServiceKey("default", "missing"))).isEmpty();
    }

    @Test
    void should_evaluate_selector_scopes_by_instance_metadata() {
        EjectionRules rules = EjectionRules.of(List.of(
                EjectionRule.selector(EjectionScope.SERVER, "10.1.1.1", "host down"),
                EjectionRule.selector(EjectionScope.ZONE, "dc-2", "zone down"),
                EjectionRule.selector(EjectionScope.GROUP, "canary", "group down")));

        // metadata 命中各自 scope；跨 service/namespace 全局生效
        assertThat(rules.isEjected(ORDER, "i1", Map.of("server", "10.1.1.1"))).isTrue();
        assertThat(rules.isEjected(ORDER, "i2", Map.of("zone", "dc-2"))).isTrue();
        assertThat(rules.isEjected(PAY, "p1", Map.of("group", "canary"))).isTrue();
        // 同名值跨 scope 不串（server 值不命中 zone 规则）
        assertThat(rules.isEjected(ORDER, "i1", Map.of("zone", "10.1.1.1"))).isFalse();
        // 不带键 = 不参与该级摘除
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isFalse();
        assertThat(rules.isEjected(ORDER, "i1", null)).isFalse();
        // 键值不匹配
        assertThat(rules.isEjected(ORDER, "i1", Map.of("server", "10.1.1.2"))).isFalse();
    }

    @Test
    void should_combine_instance_and_selector_scopes() {
        EjectionRules rules = EjectionRules.EMPTY
                .with(rule(ORDER, "i1"))
                .with(EjectionRule.selector(EjectionScope.ZONE, "dc-2", "zone down"));

        // instance 级 + selector 级同时命中（任一命中即摘除）
        assertThat(rules.isEjected(ORDER, "i1", Map.of())).isTrue();
        assertThat(rules.isEjected(ORDER, "i9", Map.of("zone", "dc-2"))).isTrue();
        assertThat(rules.isEjected(PAY, "p9", Map.of("zone", "dc-2"))).isTrue();
        assertThat(rules.isEjected(ORDER, "i9", Map.of("zone", "dc-1"))).isFalse();

        // selector 增量幂等 + 恢复
        assertThat(rules.with(EjectionRule.selector(EjectionScope.ZONE, "dc-2", "dup")))
                .isSameAs(rules);
        EjectionRules recovered = rules.without(new EjectionTarget(EjectionScope.ZONE, null, null, "dc-2"));
        assertThat(recovered.isEjected(ORDER, "i9", Map.of("zone", "dc-2"))).isFalse();
        assertThat(recovered.isEjected(ORDER, "i1", Map.of())).isTrue(); // instance 级不受影响
        // 未摘除 selector 恢复幂等
        assertThat(recovered.without(new EjectionTarget(EjectionScope.ZONE, null, null, "dc-2")))
                .isSameAs(recovered);
    }

    @Test
    void should_expose_selectors_for_diff_equality() {
        EjectionRules withZone = EjectionRules.EMPTY.with(EjectionRule.selector(EjectionScope.ZONE, "dc-2", "z"));
        EjectionRules withZoneDup = withZone.with(EjectionRule.selector(EjectionScope.ZONE, "dc-2", "dup"));
        EjectionRules withoutZone = withZone.without(new EjectionTarget(EjectionScope.ZONE, null, null, "dc-2"));

        assertThat(withZone.selectors().get(EjectionScope.ZONE)).containsExactly("dc-2");
        assertThat(withoutZone.selectors()).isEmpty();
        // selector 值集相等性：扇出判定依据（值集变化才全服务枚举）
        assertThat(withZoneDup.selectors().equals(withZone.selectors())).isTrue();
        assertThat(withoutZone.selectors().equals(withZone.selectors())).isFalse();
    }

    private static EjectionRule rule(ServiceKey service, String instanceId) {
        return EjectionRule.instance(service, instanceId, "test");
    }
}
