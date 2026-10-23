package org.mydotey.ai.artemis.registry.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.http.HttpStatus;

/** /cluster/nodes readiness 门控：未就绪 503（未就绪节点不作寻址应答者）；就绪 200 由 ClusterNodesHttpIT 覆盖。 */
class ClusterNodesControllerTest {

    @Test
    void should_refuseWith503_when_notReady() {
        ReadinessGate gate = new ReadinessGate(); // 未 markReady = STARTING
        ClusterNodesController controller = new ClusterNodesController(null, gate);

        assertThat(controller.clusterNodes().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
