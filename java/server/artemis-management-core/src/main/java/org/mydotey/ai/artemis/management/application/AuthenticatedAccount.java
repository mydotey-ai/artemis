package org.mydotey.ai.artemis.management.application;

import org.mydotey.ai.artemis.management.domain.Role;

/** 认证通过的账户主体（v1.0 phase-2）：判权与审计 operator 的事实源。 */
public record AuthenticatedAccount(String username, Role role) {}
