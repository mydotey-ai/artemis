package org.mydotey.ai.artemis.management.application;

import java.time.Clock;
import java.util.List;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AccountAdminException;
import org.mydotey.ai.artemis.management.domain.AccountStore;
import org.mydotey.ai.artemis.management.domain.PasswordHasher;
import org.mydotey.ai.artemis.management.domain.Role;

/**
 * 用户管理应用服务（v1.0 phase-2，仅 DB 态装配）：多账户 CRUD + 角色分配 + admin 重置密码。
 * 保护规则：最后 ADMIN 不可降级/删除、不可删自己；每次凭证/角色变更即时清认证缓存槽。
 * 审计由接入层负责（同 AccountController 先例）。
 */
public final class UserAccountService {

    private final AccountStore store;
    private final DbAccountAuthService authService;
    private final Clock clock;

    public UserAccountService(AccountStore store, DbAccountAuthService authService, Clock clock) {
        this.store = store;
        this.authService = authService;
        this.clock = clock;
    }

    public List<Account> list() {
        return store.list();
    }

    public Account create(String username, String rawPassword, Role role) {
        requireValidUsername(username);
        DbAccountAuthService.requireValidPassword(rawPassword);
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (store.findByUsername(username).isPresent()) {
            throw new AccountAdminException(
                    AccountAdminException.ALREADY_EXISTS, "account already exists: " + username);
        }
        Account account =
                new Account(username, PasswordHasher.hash(rawPassword), role, clock.instant(), clock.instant());
        // 并发建户竞态由 DB 唯一键兜底（实现翻译 ALREADY_EXISTS）
        store.insert(account);
        return account;
    }

    public void changeRole(String username, Role role) {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        Account target = requireAccount(username);
        if (target.role() == Role.ADMIN && role != Role.ADMIN && adminCount() == 1) {
            throw new AccountAdminException(
                    AccountAdminException.LAST_ADMIN_REQUIRED, "cannot demote the last ADMIN account");
        }
        store.updateRole(username, role, clock.instant());
        authService.invalidate(username);
    }

    public void resetPassword(String username, String newPassword) {
        requireAccount(username);
        DbAccountAuthService.requireValidPassword(newPassword);
        store.updatePasswordHash(username, PasswordHasher.hash(newPassword), clock.instant());
        authService.invalidate(username);
    }

    public void delete(String username, String operator) {
        Account target = requireAccount(username);
        if (username.equals(operator)) {
            throw new AccountAdminException(
                    AccountAdminException.SELF_DELETE, "cannot delete the authenticated account itself");
        }
        if (target.role() == Role.ADMIN && adminCount() == 1) {
            throw new AccountAdminException(
                    AccountAdminException.LAST_ADMIN_REQUIRED, "cannot delete the last ADMIN account");
        }
        store.delete(username);
        authService.invalidate(username);
    }

    private Account requireAccount(String username) {
        return store.findByUsername(username)
                .orElseThrow(() ->
                        new AccountAdminException(AccountAdminException.NOT_FOUND, "account not found: " + username));
    }

    private long adminCount() {
        // ponytail: 最后 ADMIN 保护为 check-then-act，并发降级/删除（尤其多副本）可绕过——
        // 管理面低频操作接受该窗口，锁死恢复路径 = DBA 直接 SQL；真需硬保证再上 DB 行锁事务
        return store.list().stream()
                .filter(account -> account.role() == Role.ADMIN)
                .count();
    }

    private static void requireValidUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 64 || username.contains("/")) {
            throw new IllegalArgumentException("username must be non-blank, at most 64 characters, and contain no '/'");
        }
    }
}
