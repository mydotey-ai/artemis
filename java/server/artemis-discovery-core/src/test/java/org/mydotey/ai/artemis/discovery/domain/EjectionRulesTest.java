package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EjectionRulesTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    @Test
    void should_apply_snapshot_as_replacement() {
        EjectionRules rules = EjectionRules.of(List.of(rule(ORDER, "i1"), rule(PAY, "p1")));
        assertThat(rules.isEjected(ORDER, "i1")).isTrue();
        assertThat(rules.isEjected(ORDER, "i2")).isFalse();
        assertThat(rules.isEmpty()).isFalse();

        // 快照替换：旧规则整体失效
        rules = EjectionRules.of(List.of(rule(ORDER, "i9")));
        assertThat(rules.isEjected(ORDER, "i1")).isFalse();
        assertThat(rules.isEjected(PAY, "p1")).isFalse();
        assertThat(rules.isEjected(ORDER, "i9")).isTrue();
    }

    @Test
    void should_apply_delta_with_and_without() {
        EjectionRules rules = EjectionRules.EMPTY;

        rules = rules.with(rule(ORDER, "i1"));
        assertThat(rules.isEjected(ORDER, "i1")).isTrue();

        // 幂等：已存在返回自身
        assertThat(rules.with(rule(ORDER, "i1"))).isSameAs(rules);

        rules = rules.without(ORDER, "i1");
        assertThat(rules.isEjected(ORDER, "i1")).isFalse();

        // 幂等：未摘除返回自身
        assertThat(rules.without(ORDER, "i1")).isSameAs(rules);
    }

    @Test
    void should_enumerate_services_and_instances_for_diff() {
        EjectionRules rules = EjectionRules.of(List.of(rule(ORDER, "i1"), rule(ORDER, "i2"), rule(PAY, "p1")));

        assertThat(rules.services()).containsExactlyInAnyOrder(ORDER, PAY);
        assertThat(rules.instances(ORDER)).containsExactlyInAnyOrder("i1", "i2");
        assertThat(rules.instances(PAY)).containsExactly("p1");
        assertThat(rules.instances(new ServiceKey("default", "missing"))).isEmpty();
    }

    private static EjectionRule rule(ServiceKey service, String instanceId) {
        return new EjectionRule(service, instanceId, "test");
    }
}
