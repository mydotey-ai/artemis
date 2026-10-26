package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 路由规则表行映射（infrastructure，ADR-015）：领域 {@code RouteRule} 不带持久化注解；
 * id 自增主键；targets 列存 JSON 数组字符串（StoreImpl 序列化）。
 */
@Data
@TableName("artemis_route_rule")
public class RouteRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String namespace;

    private String service;

    private String name;

    private String status;

    private String targets;

    private String operator;

    private Instant createdAt;

    private Instant updatedAt;
}
