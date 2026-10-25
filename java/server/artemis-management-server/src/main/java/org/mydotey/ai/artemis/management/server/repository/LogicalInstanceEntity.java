package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 逻辑实例表行映射（infrastructure，ADR-015）：领域 {@code LogicalInstance} 不带持久化注解；
 * id 自增主键；metadata 列存 JSON 字符串（StoreImpl 序列化）。
 */
@Data
@TableName("artemis_logical_instance")
public class LogicalInstanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String namespace;

    private String service;

    private String instanceId;

    private String host;

    private int port;

    private String status;

    private int weight;

    private String metadata;

    private Instant createdAt;

    private Instant updatedAt;
}
