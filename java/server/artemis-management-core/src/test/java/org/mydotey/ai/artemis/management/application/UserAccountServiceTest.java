package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAdminException;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;
import org.mydotey.ai.artemis.management.domain.Role;

/** 用户管理应用服务（v1.0 phase-2）：校验、保护规则与缓存失效联动。 */
class UserAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private final DbAccountAuthServiceTest.StubAccountStore store = new DbAccountAuthServiceTest.StubAccountStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DbAccountAuthService authService = new DbAccountAuthService(store, clock);
    private final UserAccountService service = new UserAccountService(store, authService, clock);

    @Test
    void should_create_and_list_accounts_without_losing_role() {
        store.put(admin());
        service.create("ops", "ops-pass-123", Role.OPERATOR);
        service.create("viewer", "viewer-pass-1", Role.VIEWER);

        assertThat(service.list()).extracting(Account::username).containsExactly("artemis", "ops", "viewer");
        assertThat(authService.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.OPERATOR));
    }

    @Test
    void should_reject_when_username_or_password_or_role_invalid() {
        assertThatThrownBy(() -> service.create(" ", "valid-pass-1", Role.VIEWER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("a".repeat(65), "valid-pass-1", Role.VIEWER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("a/b", "valid-pass-1", Role.VIEWER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("ops", "short", Role.VIEWER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("ops", "valid-pass-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.accounts).isEmpty();
    }

    @Test
    void should_reject_when_account_already_exists() {
        store.put(admin());

        assertThatThrownBy(() -> service.create("artemis", "valid-pass-1", Role.VIEWER))
                .isInstanceOf(AccountAdminException.class)
                .extracting(e -> ((AccountAdminException) e).code())
                .isEqualTo(AccountAdminException.ALREADY_EXISTS);
    }

    @Test
    void should_reject_when_target_account_absent() {
        assertThatThrownBy(() -> service.changeRole("ghost", Role.VIEWER))
                .isInstanceOf(AccountAdminException.class)
                .extracting(e -> ((AccountAdminException) e).code())
                .isEqualTo(AccountAdminException.NOT_FOUND);
    }

    @Test
    void should_reject_when_demoting_last_admin() {
        store.put(admin());

        assertThatThrownBy(() -> service.changeRole("artemis", Role.OPERATOR))
                .isInstanceOf(AccountAdminException.class)
                .extracting(e -> ((AccountAdminException) e).code())
                .isEqualTo(AccountAdminException.LAST_ADMIN_REQUIRED);
    }

    @Test
    void should_allow_demotion_when_other_admin_remains() {
        store.put(admin());
        store.put(DbAccountAuthServiceTest.account("admin2", "admin2-pass-1", Role.ADMIN));

        service.changeRole("artemis", Role.OPERATOR);

        assertThat(store.accounts.get("artemis").role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void should_reject_when_deleting_self() {
        store.put(admin());
        store.put(DbAccountAuthServiceTest.account("admin2", "admin2-pass-1", Role.ADMIN));

        assertThatThrownBy(() -> service.delete("artemis", "artemis"))
                .isInstanceOf(AccountAdminException.class)
                .extracting(e -> ((AccountAdminException) e).code())
                .isEqualTo(AccountAdminException.SELF_DELETE);
    }

    @Test
    void should_reject_when_deleting_last_admin() {
        store.put(admin());
        store.put(DbAccountAuthServiceTest.account("ops", "ops-pass-123", Role.OPERATOR));

        assertThatThrownBy(() -> service.delete("artemis", "ops"))
                .isInstanceOf(AccountAdminException.class)
                .extracting(e -> ((AccountAdminException) e).code())
                .isEqualTo(AccountAdminException.LAST_ADMIN_REQUIRED);
    }

    @Test
    void should_invalidate_credential_when_role_changed_reset_or_deleted() {
        store.put(admin());
        service.create("ops", "ops-pass-123", Role.OPERATOR);
        assertThat(authService.verify("ops", "ops-pass-123")).isPresent();

        // 改角色：缓存即时失效，重读新角色
        service.changeRole("ops", Role.VIEWER);
        assertThat(authService.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.VIEWER));

        // 重置密码：旧密码立即失效
        service.resetPassword("ops", "reset-pass-99");
        assertThat(authService.verify("ops", "ops-pass-123")).isEmpty();
        assertThat(authService.verify("ops", "reset-pass-99")).isPresent();

        // 删户：凭证立即失效
        service.delete("ops", "artemis");
        assertThat(authService.verify("ops", "reset-pass-99")).isEmpty();
    }

    @Test
    void should_reset_password_hash_with_hasher() {
        store.put(admin());
        service.create("ops", "ops-pass-123", Role.OPERATOR);

        service.resetPassword("ops", "reset-pass-99");

        assertThat(PasswordHasher.matches(
                        "reset-pass-99", store.accounts.get("ops").passwordHash()))
                .isTrue();
    }

    private static Account admin() {
        return DbAccountAuthServiceTest.account("artemis", "admin-pass-1", Role.ADMIN);
    }
}
