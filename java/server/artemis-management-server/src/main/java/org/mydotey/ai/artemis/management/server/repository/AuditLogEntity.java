package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 审计表行映射（infrastructure，ADR-015）；id 自增主键回填 = sequence。 */
@Data
@TableName("artemis_audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String operation;

    private String target;

    private String operator;

    private String result;

    private String detail;

    private Instant createdAt;
}
