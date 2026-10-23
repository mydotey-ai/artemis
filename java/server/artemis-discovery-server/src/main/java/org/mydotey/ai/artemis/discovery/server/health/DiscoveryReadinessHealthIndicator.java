package org.mydotey.ai.artemis.discovery.server.health;

import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** readiness 门控的 actuator 出口：首轮同步完成前 OUT_OF_SERVICE，完成后 UP。 */
public final class DiscoveryReadinessHealthIndicator implements HealthIndicator {

    private final DiscoveryReadinessGate readinessGate;

    public DiscoveryReadinessHealthIndicator(DiscoveryReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public Health health() {
        return readinessGate.isReady()
                ? Health.up().build()
                : Health.outOfService().build();
    }
}
