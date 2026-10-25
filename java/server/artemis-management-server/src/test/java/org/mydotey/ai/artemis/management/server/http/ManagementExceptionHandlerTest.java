package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
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

    @Test
    void should_map_account_failures_when_advice_handles() {
        // 账户存储不可用：fail-closed 向 API 面延伸（ADR-021）
        assertThat(handler.accountAccess(new AccountAccessException(
                                AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "down", null))
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        // 内存态改密：双轨不适用（ADR-021）
        assertThat(handler.memoryModePasswordChange(new MemoryModePasswordChangeException())
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
    }
}
