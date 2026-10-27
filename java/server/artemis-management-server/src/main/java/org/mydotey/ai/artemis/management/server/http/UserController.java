package org.mydotey.ai.artemis.management.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.mydotey.ai.artemis.management.application.UserAccountService;
import org.mydotey.ai.artemis.management.domain.Account;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.Role;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：用户管理 API（v1.0 phase-2 RBAC，全部 user:manage，判权由 RbacAuthorizationFilter 完成）。
 * 仅 DB 态闭环；内存态 409（同改密先例）。账户管理操作全量进审计（尽力而为例行语义）。
 * 契约见 docs/api/admin-api.md §5。
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1")
public final class UserController {

    private final PersistenceInfo persistenceInfo;
    private final ObjectProvider<UserAccountService> userAccountsProvider;
    private final AuditLogStore auditLogs;
    private final Clock clock;

    public UserController(
            PersistenceInfo persistenceInfo,
            ObjectProvider<UserAccountService> userAccountsProvider,
            AuditLogStore auditLogs,
            Clock clock) {
        this.persistenceInfo = persistenceInfo;
        this.userAccountsProvider = userAccountsProvider;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    @GetMapping("/users")
    public ListUsersResponse list() {
        return new ListUsersResponse(
                userAccounts().list().stream().map(UserResponse::from).toList());
    }

    @PostMapping("/users")
    public UserResponse create(
            @RequestBody(required = false) CreateUserRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        if (request == null || request.username() == null || request.role() == null) {
            throw new IllegalArgumentException("username, password and role are required");
        }
        Account created = userAccounts().create(request.username(), request.password(), parseRole(request.role()));
        audit("CREATE_USER", created.username(), operator);
        return UserResponse.from(created);
    }

    @PutMapping("/users/{username}/role")
    public void changeRole(
            @PathVariable("username") String username,
            @RequestBody(required = false) RoleRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        if (request == null || request.role() == null) {
            throw new IllegalArgumentException("role is required");
        }
        userAccounts().changeRole(username, parseRole(request.role()));
        audit("UPDATE_USER_ROLE", username, operator);
    }

    @PutMapping("/users/{username}/password")
    public void resetPassword(
            @PathVariable("username") String username,
            @RequestBody(required = false) ResetPasswordRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        if (request == null || request.newPassword() == null) {
            throw new IllegalArgumentException("newPassword is required");
        }
        userAccounts().resetPassword(username, request.newPassword());
        audit("RESET_USER_PASSWORD", username, operator);
    }

    @DeleteMapping("/users/{username}")
    public void delete(
            @PathVariable("username") String username,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        userAccounts().delete(username, operator);
        audit("DELETE_USER", username, operator);
    }

    /** 预置角色列表（console 表单用）。 */
    @GetMapping("/roles")
    public ListRolesResponse roles() {
        userAccounts(); // 内存态同 409（角色列表服务用户管理表单）
        return new ListRolesResponse(
                List.of(Role.values()).stream().map(Enum::name).toList());
    }

    private UserAccountService userAccounts() {
        if (persistenceInfo.mode() != PersistenceInfo.Mode.DATABASE) {
            throw new MemoryModeUserAdminException();
        }
        return userAccountsProvider.getObject();
    }

    private static Role parseRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown role: " + role);
        }
    }

    /** 账户管理审计：尽力而为（失败不阻塞已完成的账户操作）。 */
    private void audit(String operation, String username, String operator) {
        try {
            auditLogs.append(
                    new AuditLog(operation, "account:" + username, operator, "SUCCESS", null, clock.instant(), 0));
        } catch (RuntimeException e) {
            log.error("audit log append failed: operation={}, account={}", operation, username, e);
        }
    }

    record CreateUserRequest(String username, String password, String role) {}

    record RoleRequest(@JsonProperty("role") String role) {}

    record ResetPasswordRequest(@JsonProperty("newPassword") String newPassword) {}

    /** 展示面不含 passwordHash。 */
    record UserResponse(String username, Role role, Instant createdAt, Instant updatedAt) {
        static UserResponse from(Account account) {
            return new UserResponse(account.username(), account.role(), account.createdAt(), account.updatedAt());
        }
    }

    record ListUsersResponse(List<UserResponse> users) {}

    record ListRolesResponse(List<String> roles) {}
}
