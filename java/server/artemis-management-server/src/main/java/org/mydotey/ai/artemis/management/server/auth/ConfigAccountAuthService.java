package org.mydotey.ai.artemis.management.server.auth;

import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.application.AccountAuthService;

/**
 * 内存态认证裁决（ADR-021 双轨）：配置凭证比对，v0.1 行为零变化——凭证动态（每请求现取，
 * 支持运行期轮换）、空白配置凭证拒绝（同 headerValueOrNull 语义）。
 */
public final class ConfigAccountAuthService implements AccountAuthService {

    private final ManagementAuthConfig authConfig;

    public ConfigAccountAuthService(ManagementAuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    @Override
    public boolean verify(String username, String password) {
        String expected = BasicAuth.headerValueOrNull(authConfig.username(), authConfig.password());
        String presented = BasicAuth.headerValueOrNull(username, password);
        return expected != null && presented != null && BasicAuth.matches(presented, expected);
    }

    @Override
    public String changePassword(String oldPassword, String newPassword) {
        // 内存态凭证事实源是配置（ADR-021），无改密语义；接入层以 409 表达
        throw new UnsupportedOperationException("password change is only available in database mode");
    }
}
