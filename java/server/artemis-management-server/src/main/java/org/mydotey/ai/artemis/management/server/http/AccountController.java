package org.mydotey.ai.artemis.management.server.http;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：账户管理 API（v0.4 phase-2，ADR-021）。改密为 DB 态闭环（默认密码消除出口）；
 * 内存态凭证事实源是配置，端点 409。改密成功进审计（phase-3 补齐）。契约见 docs/api/admin-api.md。
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/account")
public final class AccountController {

    private final AccountAuthService accountAuthService;
    private final PersistenceInfo persistenceInfo;
    private final AuditLogStore auditLogs;
    private final Clock clock;

    public AccountController(
            AccountAuthService accountAuthService,
            PersistenceInfo persistenceInfo,
            AuditLogStore auditLogs,
            Clock clock) {
        this.accountAuthService = accountAuthService;
        this.persistenceInfo = persistenceInfo;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    @PutMapping("/password")
    public ChangePasswordResponse changePassword(@RequestBody(required = false) ChangePasswordRequest request) {
        if (persistenceInfo.mode() != PersistenceInfo.Mode.DATABASE) {
            throw new MemoryModePasswordChangeException();
        }
        if (request == null || isBlank(request.oldPassword()) || isBlank(request.newPassword())) {
            throw new IllegalArgumentException("oldPassword and newPassword are required");
        }
        String username = accountAuthService.changePassword(request.oldPassword(), request.newPassword());
        audit(username);
        return new ChangePasswordResponse(username);
    }

    /** 改密审计：尽力而为（失败不阻塞改密结果——凭证已变更，重试只会失败）。 */
    private void audit(String username) {
        try {
            auditLogs.append(new AuditLog(
                    "CHANGE_PASSWORD", "account:" + username, username, "SUCCESS", null, clock.instant(), 0));
        } catch (RuntimeException e) {
            log.error("audit log append failed: operation=CHANGE_PASSWORD, account={}", username, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ChangePasswordRequest(String oldPassword, String newPassword) {}

    record ChangePasswordResponse(String username) {}
}
