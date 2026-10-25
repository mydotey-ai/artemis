package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryAuditLogStore;

class AccountControllerTest {

    private final StubAuthService authService = new StubAuthService();
    private final InMemoryAuditLogStore auditLogs = new InMemoryAuditLogStore();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
    private final AccountController controller = new AccountController(
            authService, new PersistenceInfo(PersistenceInfo.Mode.DATABASE, "PostgreSQL", "3"), auditLogs, clock);

    @Test
    void should_change_password_when_old_valid() {
        AccountController.ChangePasswordResponse response =
                controller.changePassword(new AccountController.ChangePasswordRequest("old-s3cret-1", "new-s3cret-9"));

        assertThat(response.username()).isEqualTo("artemis");
        assertThat(authService.changeCalls).isEqualTo(1);
    }

    @Test
    void should_return_409_when_memory_mode_change_password() {
        AccountController memoryMode = new AccountController(authService, PersistenceInfo.memory(), auditLogs, clock);

        assertThatThrownBy(() ->
                        memoryMode.changePassword(new AccountController.ChangePasswordRequest("old", "new-s3cret-9")))
                .isInstanceOf(MemoryModePasswordChangeException.class);
        assertThat(authService.changeCalls).isZero();
    }

    @Test
    void should_audit_successful_password_change() {
        controller.changePassword(new AccountController.ChangePasswordRequest("old-s3cret-1", "new-s3cret-9"));

        List<AuditLog> logs = auditLogs.find("CHANGE_PASSWORD", 10);
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.operation()).isEqualTo("CHANGE_PASSWORD");
            assertThat(log.target()).isEqualTo("account:artemis");
            assertThat(log.operator()).isEqualTo("artemis");
            assertThat(log.result()).isEqualTo("SUCCESS");
        });
    }

    @Test
    void should_return_400_when_change_request_invalid() {
        assertThatThrownBy(() -> controller.changePassword(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        controller.changePassword(new AccountController.ChangePasswordRequest(null, "new-s3cret-9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.changePassword(new AccountController.ChangePasswordRequest("old", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_propagate_service_validation_when_new_password_too_short() {
        authService.rejectChange = true;

        assertThatThrownBy(() ->
                        controller.changePassword(new AccountController.ChangePasswordRequest("old-s3cret-1", "short")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class StubAuthService implements AccountAuthService {

        int changeCalls;
        boolean rejectChange;

        @Override
        public boolean verify(String username, String password) {
            return false;
        }

        @Override
        public String changePassword(String oldPassword, String newPassword) {
            changeCalls++;
            if (rejectChange) {
                throw new IllegalArgumentException("newPassword must be at least 8 characters");
            }
            return "artemis";
        }
    }
}
