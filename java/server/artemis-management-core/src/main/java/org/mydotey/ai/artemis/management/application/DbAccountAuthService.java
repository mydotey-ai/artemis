package org.mydotey.ai.artemis.management.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;

/**
 * DB 态账户认证（ADR-021）：账户表为唯一凭证源；seed 由装配层在 migration 后调用一次。
 *
 * <p>成功凭证单槽缓存：正确路径每 TTL 付一次 PBKDF2（百 ms 级），错误路径每次全额计算
 * （暴力破解无缓存折扣）；改密即时清槽（旧凭证立即失效）。
 */
@Slf4j
public final class DbAccountAuthService implements AccountAuthService {

    static final int MIN_PASSWORD_LENGTH = 8;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final AccountStore store;
    private final Clock clock;

    // ponytail: 单槽缓存只服务单管理员；多用户（RBAC v1.0）改按账户键控缓存
    private volatile CachedCredential cached;

    private record CachedCredential(String token, Instant expiresAt) {}

    public DbAccountAuthService(AccountStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public boolean verify(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String token = cacheToken(username, password);
        CachedCredential current = cached;
        if (current != null
                && clock.instant().isBefore(current.expiresAt())
                && MessageDigest.isEqual(
                        current.token().getBytes(StandardCharsets.US_ASCII),
                        token.getBytes(StandardCharsets.US_ASCII))) {
            return true;
        }
        Account account = store.findAny().orElse(null);
        if (account == null) {
            return false;
        }
        boolean accepted =
                username.equals(account.username()) && PasswordHasher.matches(password, account.passwordHash());
        if (accepted) {
            cached = new CachedCredential(token, clock.instant().plus(CACHE_TTL));
        } else {
            // 凭证不进日志（ADR-009 语义）
            log.warn("management authentication rejected for username <{}>", username);
        }
        return accepted;
    }

    @Override
    public String changePassword(String oldPassword, String newPassword) {
        if (oldPassword == null || newPassword == null) {
            throw new IllegalArgumentException("oldPassword and newPassword are required");
        }
        if (newPassword.codePointCount(0, newPassword.length()) < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("newPassword must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        Account account = store.findAny().orElse(null);
        if (account == null) {
            throw new IllegalStateException("no account present; seed must run before password change");
        }
        if (!PasswordHasher.matches(oldPassword, account.passwordHash())) {
            throw new IllegalArgumentException("oldPassword does not match");
        }
        store.updatePasswordHash(account.username(), PasswordHasher.hash(newPassword), clock.instant());
        cached = null;
        log.info("management account password changed (username <{}>)", account.username());
        return account.username();
    }

    /**
     * DB 态首启 seed（ADR-021）：表空时将配置凭证一次性物化——已配置自定义凭证的部署升级
     * 无缝；表非空永不覆盖（此后配置凭证在 DB 态失效）。
     */
    public void seedIfAbsent(String configuredUsername, String configuredPassword) {
        if (store.existsAny()) {
            log.info("management account already present, configured credentials are ignored in database mode");
            return;
        }
        Instant now = clock.instant();
        store.insert(new Account(configuredUsername, PasswordHasher.hash(configuredPassword), now, now));
        log.warn(
                "management account seeded from configuration (username <{}>); "
                        + "the account table is the credential source from now on",
                configuredUsername);
    }

    /** 启动告警用（ADR-009 默认密码检测延续）：给定明文是否为当前账户密码。 */
    public boolean passwordInUse(String rawPassword) {
        return store.findAny()
                .map(account -> PasswordHasher.matches(rawPassword, account.passwordHash()))
                .orElse(false);
    }

    /** 缓存键为凭证摘要（不存明文），常量时间比较。 */
    private static String cacheToken(String username, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(username.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            return Base64.getEncoder().encodeToString(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
