package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账户存储端口（v0.4 → v1.0 phase-2 多账户）：DB 态实现居 server（MyBatis-Plus）；
 * 内存态无实现——账户不持久化，认证裁决走配置凭证。
 */
public interface AccountStore {

    /** 是否存在任何账户（seed 判定）。 */
    boolean existsAny();

    Optional<Account> findByUsername(String username);

    /** 字典序全量列表（用户管理展示面；含 hash，剥离由调用方负责）。 */
    List<Account> list();

    /** 并发建户竞态由 DB 唯一键兜底（实现翻译为 {@link AccountAdminException} ALREADY_EXISTS）。 */
    void insert(Account account);

    /** 按用户名更新密码哈希。 */
    void updatePasswordHash(String username, String passwordHash, Instant updatedAt);

    void updateRole(String username, Role role, Instant updatedAt);

    void delete(String username);
}
