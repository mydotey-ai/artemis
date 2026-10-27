package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;

/**
 * 管理账户（v0.4 单管理员 → v1.0 phase-2 多账户 + RBAC 角色）：DB 态管理访问面认证的
 * 唯一事实源。passwordHash 为自描述 PBKDF2 串（格式见 {@link PasswordHasher}）；
 * role 为预置角色，权限矩阵见 {@link Role}。
 */
public record Account(String username, String passwordHash, Role role, Instant createdAt, Instant updatedAt) {}
