package org.mydotey.ai.artemis.management.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAdminException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;
import org.mydotey.ai.artemis.management.domain.Role;

/**
 * DB 态账户认证（ADR-021；v1.0 phase-2 多账户）：账户表为唯一凭证源；seed 由装配层在
 * migration 后调用一次（role=ADMIN）。
 *
 * <p>成功凭证按用户名键控缓存：正确路径每 TTL 付一次 PBKDF2（百 ms 级），错误路径每次
 * 全额计算（暴力破解无缓存折扣）；缓存仅认证成功条目，容量 = 有效账户数天然有界。
 * 改密即时清槽；角色变更/重置密码/删户经 {@link #invalidate} 清槽（权限即时生效，不等 TTL）。
 */
@Slf4j
public final class DbAccountAuthService implements AccountAuthService {

    static final int MIN_PASSWORD_LENGTH = 8;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final AccountStore store;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedCredential> cache = new ConcurrentHashMap<>();

    private record CachedCredential(String token, Role role, Instant expiresAt) {}

    public DbAccountAuthService(AccountStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Optional<AuthenticatedAccount> verify(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        String token = cacheToken(username, password);
        CachedCredential cached = cache.get(username);
        if (cached != null
                && clock.instant().isBefore(cached.expiresAt())
                && MessageDigest.isEqual(
                        cached.token().getBytes(StandardCharsets.US_ASCII),
                        token.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(new AuthenticatedAccount(username, cached.role()));
        }
        Account account = store.findByUsername(username).orElse(null);
        if (account == null) {
            return Optional.empty();
        }
        if (!PasswordHasher.matches(password, account.passwordHash())) {
            // 凭证不进日志（ADR-009 语义）
            log.warn("management authentication rejected for username <{}>", username);
            return Optional.empty();
        }
        // ponytail: 与 invalidate 存在回填竞态（重置/改角色瞬间的并发认证可回填旧凭证/旧角色，
        // 残留 ≤ TTL 60s；v0.4 单槽实现同款），真需即时硬一致再引入版本化缓存
        cache.put(
                username,
                new CachedCredential(token, account.role(), clock.instant().plus(CACHE_TTL)));
        return Optional.of(new AuthenticatedAccount(username, account.role()));
    }

    @Override
    public String changePassword(String username, String oldPassword, String newPassword) {
        if (oldPassword == null) {
            throw new IllegalArgumentException("oldPassword is required");
        }
        requireValidPassword(newPassword);
        Account account = store.findByUsername(username)
                .orElseThrow(() ->
                        new AccountAdminException(AccountAdminException.NOT_FOUND, "account not found: " + username));
        if (!PasswordHasher.matches(oldPassword, account.passwordHash())) {
            throw new IllegalArgumentException("oldPassword does not match");
        }
        store.updatePasswordHash(username, PasswordHasher.hash(newPassword), clock.instant());
        invalidate(username);
        log.info("management account password changed (username <{}>)", username);
        return username;
    }

    /** 使指定账户的认证缓存失效（改密/重置/角色变更/删户调用）。 */
    public void invalidate(String username) {
        if (username != null) {
            cache.remove(username);
        }
    }

    /**
     * DB 态首启 seed（ADR-021）：表空时将配置凭证一次性物化——已配置自定义凭证的部署升级
     * 无缝；表非空永不覆盖（此后配置凭证在 DB 态失效）。seed 账户为 ADMIN（v0.4 单管理员
     * 升级经 V7 迁移 role 列默认值同语义）。
     */
    public void seedIfAbsent(String configuredUsername, String configuredPassword) {
        if (store.existsAny()) {
            log.info("management account already present, configured credentials are ignored in database mode");
            return;
        }
        Instant now = clock.instant();
        store.insert(new Account(configuredUsername, PasswordHasher.hash(configuredPassword), Role.ADMIN, now, now));
        log.warn(
                "management account seeded from configuration (username <{}>); "
                        + "the account table is the credential source from now on",
                configuredUsername);
    }

    /** 启动告警用（ADR-009 默认密码检测延续）：给定明文是否为任一账户密码。 */
    public boolean passwordInUse(String rawPassword) {
        return store.list().stream().anyMatch(account -> PasswordHasher.matches(rawPassword, account.passwordHash()));
    }

    static void requireValidPassword(String newPassword) {
        if (newPassword == null || newPassword.codePointCount(0, newPassword.length()) < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("newPassword must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
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
