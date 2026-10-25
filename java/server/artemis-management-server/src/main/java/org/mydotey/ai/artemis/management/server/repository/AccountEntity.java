package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 账户表行映射（infrastructure，ADR-015）：领域 {@code Account} 不带持久化注解；
 * 可变 POJO 为 MyBatis 映射所需。
 */
@Data
@TableName("artemis_account")
public class AccountEntity {

    private String username;

    private String passwordHash;

    private Instant createdAt;

    private Instant updatedAt;
}
