package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.domain.AccountAdminException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;
import org.mydotey.ai.artemis.management.domain.Role;

class DbAccountAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final StubAccountStore store = new StubAccountStore();
    private final DbAccountAuthService service = new DbAccountAuthService(store, clock);

    @Test
    void should_verify_when_db_account_credentials_match() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        assertThat(service.verify("artemis", "old-s3cret-1")).hasValue(new AuthenticatedAccount("artemis", Role.ADMIN));
    }

    @Test
    void should_verify_multi_accounts_with_their_roles() {
        store.put(account("artemis", "admin-pass-1", Role.ADMIN));
        store.put(account("ops", "ops-pass-123", Role.OPERATOR));
        store.put(account("viewer", "viewer-pass-1", Role.VIEWER));

        assertThat(service.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.OPERATOR));
        assertThat(service.verify("viewer", "viewer-pass-1")).hasValue(new AuthenticatedAccount("viewer", Role.VIEWER));
        assertThat(service.verify("ops", "viewer-pass-1")).isEmpty();
    }

    @Test
    void should_not_verify_when_username_mismatch_or_account_absent() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        assertThat(service.verify("admin", "old-s3cret-1")).isEmpty();
        assertThat(service.verify(null, "old-s3cret-1")).isEmpty();
        assertThat(service.verify("artemis", null)).isEmpty();
        assertThat(service.verify("artemis", "wrong-pass")).isEmpty();
    }

    @Test
    void should_use_cache_when_same_credentials_within_ttl() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));
        service.verify("artemis", "old-s3cret-1");
        store.findCalls = 0;

        assertThat(service.verify("artemis", "old-s3cret-1")).isPresent();
        assertThat(store.findCalls).isZero();
    }

    @Test
    void should_bypass_cache_when_cache_expired() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));
        service.verify("artemis", "old-s3cret-1");
        store.findCalls = 0;
        clock.now = NOW.plusSeconds(61);

        assertThat(service.verify("artemis", "old-s3cret-1")).isPresent();
        assertThat(store.findCalls).isEqualTo(1);
    }

    @Test
    void should_not_serve_other_account_from_cache() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));
        store.put(account("ops", "ops-pass-123", Role.OPERATOR));
        service.verify("artemis", "old-s3cret-1");
        store.findCalls = 0;

        // 键控缓存：ops 首次认证必须落库，不命中 artemis 槽
        assertThat(service.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.OPERATOR));
        assertThat(store.findCalls).isEqualTo(1);
    }

    @Test
    void should_invalidate_cache_when_password_changed() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));
        assertThat(service.verify("artemis", "old-s3cret-1")).isPresent();

        service.changePassword("artemis", "old-s3cret-1", "new-s3cret-9");

        assertThat(service.verify("artemis", "old-s3cret-1")).isEmpty();
        assertThat(service.verify("artemis", "new-s3cret-9")).isPresent();
        assertThat(store.updateCalls).isEqualTo(1);
    }

    @Test
    void should_invalidate_cache_when_role_changed_via_invalidate() {
        store.put(account("ops", "ops-pass-123", Role.OPERATOR));
        assertThat(service.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.OPERATOR));

        // 角色变更路径（UserAccountService）：清槽后下一次认证重读角色
        store.put(account("ops", "ops-pass-123", Role.VIEWER));
        service.invalidate("ops");

        assertThat(service.verify("ops", "ops-pass-123")).hasValue(new AuthenticatedAccount("ops", Role.VIEWER));
    }

    @Test
    void should_not_verify_deleted_account_after_invalidate() {
        store.put(account("ops", "ops-pass-123", Role.OPERATOR));
        assertThat(service.verify("ops", "ops-pass-123")).isPresent();

        store.accounts.remove("ops");
        service.invalidate("ops");

        assertThat(service.verify("ops", "ops-pass-123")).isEmpty();
    }

    @Test
    void should_reject_change_when_old_password_wrong() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        assertThatThrownBy(() -> service.changePassword("artemis", "wrong", "new-s3cret-9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.updateCalls).isZero();
    }

    @Test
    void should_reject_change_when_new_password_too_short() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        assertThatThrownBy(() -> service.changePassword("artemis", "old-s3cret-1", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.changePassword("artemis", "old-s3cret-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.updateCalls).isZero();
    }

    @Test
    void should_propagate_store_failure_as_unavailable() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));
        store.failure = new AccountAccessException(AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "down", null);

        assertThatThrownBy(() -> service.verify("artemis", "old-s3cret-1")).isInstanceOf(AccountAccessException.class);
        assertThatThrownBy(() -> service.changePassword("artemis", "old-s3cret-1", "new-s3cret-9"))
                .isInstanceOf(AccountAccessException.class);
    }

    @Test
    void should_seed_admin_when_store_empty() {
        service.seedIfAbsent("custom-admin", "custom-pass");

        assertThat(store.insertCalls).isEqualTo(1);
        Account seeded = store.accounts.get("custom-admin");
        assertThat(seeded.role()).isEqualTo(Role.ADMIN);
        assertThat(PasswordHasher.matches("custom-pass", seeded.passwordHash())).isTrue();
    }

    @Test
    void should_not_seed_when_store_has_account() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        service.seedIfAbsent("custom-admin", "custom-pass");

        assertThat(store.insertCalls).isZero();
        assertThat(store.accounts).containsOnlyKeys("artemis");
    }

    @Test
    void should_detect_password_in_use() {
        store.put(account("artemis", "old-s3cret-1", Role.ADMIN));

        assertThat(service.passwordInUse("old-s3cret-1")).isTrue();
        assertThat(service.passwordInUse("other")).isFalse();
    }

    static Account account(String username, String rawPassword, Role role) {
        return new Account(username, PasswordHasher.hash(rawPassword), role, NOW, NOW);
    }

    /** 多账户内存 stub（供本测试与 UserAccountServiceTest 复用同包形态）。 */
    static final class StubAccountStore implements AccountStore {

        final Map<String, Account> accounts = new LinkedHashMap<>();
        int findCalls;
        int updateCalls;
        int insertCalls;
        AccountAccessException failure;

        void put(Account account) {
            accounts.put(account.username(), account);
        }

        @Override
        public boolean existsAny() {
            return !accounts.isEmpty();
        }

        @Override
        public Optional<Account> findByUsername(String username) {
            findCalls++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(accounts.get(username));
        }

        @Override
        public List<Account> list() {
            if (failure != null) {
                throw failure;
            }
            return new ArrayList<>(accounts.values());
        }

        @Override
        public void insert(Account inserted) {
            insertCalls++;
            if (accounts.putIfAbsent(inserted.username(), inserted) != null) {
                throw new AccountAdminException(AccountAdminException.ALREADY_EXISTS, "duplicate");
            }
        }

        @Override
        public void updatePasswordHash(String username, String passwordHash, Instant updatedAt) {
            updateCalls++;
            Account current = accounts.get(username);
            accounts.put(username, new Account(username, passwordHash, current.role(), current.createdAt(), updatedAt));
        }

        @Override
        public void updateRole(String username, Role role, Instant updatedAt) {
            Account current = accounts.get(username);
            accounts.put(username, new Account(username, current.passwordHash(), role, current.createdAt(), updatedAt));
        }

        @Override
        public void delete(String username) {
            accounts.remove(username);
        }
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
