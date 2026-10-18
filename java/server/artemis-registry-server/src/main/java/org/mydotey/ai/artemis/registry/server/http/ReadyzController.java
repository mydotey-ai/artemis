package org.mydotey.ai.artemis.registry.server.http;

import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 HTTP 就绪探针（LB 用，无认证）：未就绪 503，就绪 200。
 */
@RestController
public final class ReadyzController {

    private final ReadinessGate readinessGate;

    public ReadyzController(ReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @GetMapping("/readyz")
    public ResponseEntity<String> readyz() {
        return readinessGate.isReady()
                ? ResponseEntity.ok("ready")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("not ready");
    }
}
