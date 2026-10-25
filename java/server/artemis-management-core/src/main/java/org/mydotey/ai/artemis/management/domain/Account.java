package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;

/**
 * 单管理员账户（v0.4，ADR-021）：DB 态管理访问面认证的唯一事实源。
 * passwordHash 为自描述 PBKDF2 串（格式见 {@link PasswordHasher}）。
 */
public record Account(String username, String passwordHash, Instant createdAt, Instant updatedAt) {}
