package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 摘除记录表行映射（infrastructure，ADR-015）：领域 {@code EjectionRecord} 不带持久化注解；
 * id 为自增主键（插入后回填 = sequence）。
 */
@Data
@TableName("artemis_ejection_record")
public class EjectionRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scope;

    private String namespace;

    private String service;

    private String instanceId;

    private String targetValue;

    private String action;

    private String reason;

    private String operator;

    private Instant createdAt;
}
