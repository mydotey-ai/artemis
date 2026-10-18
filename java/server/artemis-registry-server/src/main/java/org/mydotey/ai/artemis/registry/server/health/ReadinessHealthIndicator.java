package org.mydotey.ai.artemis.registry.server.health;

import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * readiness 门控的 actuator 出口（phase-3 延后的 HTTP readiness endpoint）：
 * 快照回放完成前 OUT_OF_SERVICE，完成后 UP。
 */
public final class ReadinessHealthIndicator implements HealthIndicator {

    private final ReadinessGate readinessGate;

    public ReadinessHealthIndicator(ReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public Health health() {
        return readinessGate.isReady()
                ? Health.up().build()
                : Health.outOfService().build();
    }
}
