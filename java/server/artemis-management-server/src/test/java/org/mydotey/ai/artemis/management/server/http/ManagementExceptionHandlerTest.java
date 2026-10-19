package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.RegistryQueryException;

class ManagementExceptionHandlerTest {

    private final ManagementExceptionHandler handler = new ManagementExceptionHandler();

    @Test
    void should_map_error_status_when_advice_handles() {
        assertThat(handler.invalidArgument(new IllegalArgumentException("bad"))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(handler.invalidArgument(new IllegalArgumentException("bad"))
                        .getBody()
                        .error())
                .isEqualTo("INVALID_ARGUMENT");

        assertThat(handler.registryQuery(
                                new RegistryQueryException(RegistryQueryException.REGISTRY_UNAVAILABLE, "down"))
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        assertThat(handler.registryQuery(new RegistryQueryException(RegistryQueryException.REGISTRY_ERROR, "boom"))
                        .getStatusCode()
                        .value())
                .isEqualTo(502);
        assertThat(handler.unexpected(new IllegalStateException("x"))
                        .getStatusCode()
                        .value())
                .isEqualTo(500);
    }
}
