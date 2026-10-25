package org.mydotey.ai.artemis.management.server.http;

import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：账户管理 API（v0.4 phase-2，ADR-021）。改密为 DB 态闭环（默认密码消除出口）；
 * 内存态凭证事实源是配置，端点 409。契约见 docs/api/admin-api.md。
 */
@RestController
@RequestMapping("/admin/v1/account")
public final class AccountController {

    private final AccountAuthService accountAuthService;
    private final PersistenceInfo persistenceInfo;

    public AccountController(AccountAuthService accountAuthService, PersistenceInfo persistenceInfo) {
        this.accountAuthService = accountAuthService;
        this.persistenceInfo = persistenceInfo;
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
        return new ChangePasswordResponse(username);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ChangePasswordRequest(String oldPassword, String newPassword) {}

    record ChangePasswordResponse(String username) {}
}
