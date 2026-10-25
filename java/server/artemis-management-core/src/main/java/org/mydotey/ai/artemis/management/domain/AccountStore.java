package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * 账户存储端口（v0.4）：DB 态实现居 server（MyBatis-Plus）；内存态无实现——账户不持久化，
 * 认证裁决走配置凭证。
 */
public interface AccountStore {

    /** 是否存在任何账户（seed 判定）。 */
    boolean existsAny();

    /** 单管理员语义：字典序首行（脏多行容错，不 fail-fast）。 */
    Optional<Account> findAny();

    void insert(Account account);

    /** 按用户名更新密码哈希。 */
    void updatePasswordHash(String username, String passwordHash, Instant updatedAt);
}
