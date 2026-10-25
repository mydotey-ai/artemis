package org.mydotey.ai.artemis.management.application;

/**
 * 管理访问面认证裁决端口（v0.4，ADR-021）：DB 态实现验证账户表（唯一源），
 * 内存态实现包装配置凭证（v0.1 行为）。
 */
public interface AccountAuthService {

    /** 凭证裁决；认证后端不可用抛 {@code AccountAccessException}（接入层 503）。 */
    boolean verify(String username, String password);

    /** 改密并返回账户名；old 不匹配或 newPassword 非法抛 {@code IllegalArgumentException}。 */
    String changePassword(String oldPassword, String newPassword);
}
