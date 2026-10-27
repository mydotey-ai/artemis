package org.mydotey.ai.artemis.management.application;

import java.util.Optional;

/**
 * 管理访问面认证裁决端口（v0.4，ADR-021；v1.0 phase-2 返回 principal 供 RBAC 判权）：
 * DB 态实现验证账户表（唯一源），内存态实现包装配置凭证（v0.1 行为，固定 ADMIN）。
 */
public interface AccountAuthService {

    /** 凭证裁决；通过返回 principal，失败 empty；认证后端不可用抛 {@code AccountAccessException}（接入层 503）。 */
    Optional<AuthenticatedAccount> verify(String username, String password);

    /** 本人改密并返回账户名；old 不匹配或 newPassword 非法抛 {@code IllegalArgumentException}。 */
    String changePassword(String username, String oldPassword, String newPassword);
}
