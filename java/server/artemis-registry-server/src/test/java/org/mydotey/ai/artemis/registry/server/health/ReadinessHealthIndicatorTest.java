package org.mydotey.ai.artemis.registry.server.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;

class ReadinessHealthIndicatorTest {

    @Test
    void should_expose_readiness_as_up_when_ready() {
        ReadinessGate gate = new ReadinessGate();
        ReadinessHealthIndicator indicator = new ReadinessHealthIndicator(gate);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");

        gate.markReady();

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }
}
