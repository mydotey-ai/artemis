package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;

class DbAccountAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final StubAccountStore store = new StubAccountStore();
    private final DbAccountAuthService service = new DbAccountAuthService(store, clock);

    @Test
    void should_verify_when_db_account_credentials_match() {
        store.account = account("artemis", "old-s3cret-1");

        assertThat(service.verify("artemis", "old-s3cret-1")).isTrue();
    }

    @Test
    void should_not_verify_when_username_mismatch_or_account_absent() {
        store.account = account("artemis", "old-s3cret-1");

        assertThat(service.verify("admin", "old-s3cret-1")).isFalse();
        assertThat(service.verify(null, "old-s3cret-1")).isFalse();
        assertThat(service.verify("artemis", null)).isFalse();

        store.account = null;
        assertThat(service.verify("artemis", "old-s3cret-1")).isFalse();
    }

    @Test
    void should_use_cache_when_same_credentials_within_ttl() {
        store.account = account("artemis", "old-s3cret-1");
        service.verify("artemis", "old-s3cret-1");
        store.findCalls = 0;

        assertThat(service.verify("artemis", "old-s3cret-1")).isTrue();
        assertThat(store.findCalls).isZero();
    }

    @Test
    void should_bypass_cache_when_cache_expired() {
        store.account = account("artemis", "old-s3cret-1");
        service.verify("artemis", "old-s3cret-1");
        store.findCalls = 0;
        clock.now = NOW.plusSeconds(61);

        assertThat(service.verify("artemis", "old-s3cret-1")).isTrue();
        assertThat(store.findCalls).isEqualTo(1);
    }

    @Test
    void should_invalidate_cache_when_password_changed() {
        store.account = account("artemis", "old-s3cret-1");
        assertThat(service.verify("artemis", "old-s3cret-1")).isTrue();

        service.changePassword("old-s3cret-1", "new-s3cret-9");

        assertThat(service.verify("artemis", "old-s3cret-1")).isFalse();
        assertThat(service.verify("artemis", "new-s3cret-9")).isTrue();
        assertThat(store.updateCalls).isEqualTo(1);
    }

    @Test
    void should_reject_change_when_old_password_wrong() {
        store.account = account("artemis", "old-s3cret-1");

        assertThatThrownBy(() -> service.changePassword("wrong", "new-s3cret-9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.updateCalls).isZero();
    }

    @Test
    void should_reject_change_when_new_password_too_short() {
        store.account = account("artemis", "old-s3cret-1");

        assertThatThrownBy(() -> service.changePassword("old-s3cret-1", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.changePassword("old-s3cret-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.updateCalls).isZero();
    }

    @Test
    void should_propagate_store_failure_as_unavailable() {
        store.account = account("artemis", "old-s3cret-1");
        store.failure = new AccountAccessException(AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "down", null);

        assertThatThrownBy(() -> service.verify("artemis", "old-s3cret-1")).isInstanceOf(AccountAccessException.class);
        assertThatThrownBy(() -> service.changePassword("old-s3cret-1", "new-s3cret-9"))
                .isInstanceOf(AccountAccessException.class);
    }

    @Test
    void should_seed_when_store_empty() {
        service.seedIfAbsent("custom-admin", "custom-pass");

        assertThat(store.insertCalls).isEqualTo(1);
        assertThat(store.account.username()).isEqualTo("custom-admin");
        assertThat(PasswordHasher.matches("custom-pass", store.account.passwordHash()))
                .isTrue();
    }

    @Test
    void should_not_seed_when_store_has_account() {
        store.account = account("artemis", "old-s3cret-1");

        service.seedIfAbsent("custom-admin", "custom-pass");

        assertThat(store.insertCalls).isZero();
        assertThat(store.account.username()).isEqualTo("artemis");
    }

    @Test
    void should_detect_password_in_use() {
        store.account = account("artemis", "old-s3cret-1");

        assertThat(service.passwordInUse("old-s3cret-1")).isTrue();
        assertThat(service.passwordInUse("other")).isFalse();
    }

    private static Account account(String username, String rawPassword) {
        return new Account(username, PasswordHasher.hash(rawPassword), NOW, NOW);
    }

    private static final class StubAccountStore implements AccountStore {

        Account account;
        int findCalls;
        int updateCalls;
        int insertCalls;
        AccountAccessException failure;

        @Override
        public boolean existsAny() {
            return account != null;
        }

        @Override
        public Optional<Account> findAny() {
            findCalls++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(account);
        }

        @Override
        public void insert(Account inserted) {
            insertCalls++;
            account = inserted;
        }

        @Override
        public void updatePasswordHash(String username, String passwordHash, Instant updatedAt) {
            updateCalls++;
            account = new Account(username, passwordHash, account.createdAt(), updatedAt);
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
