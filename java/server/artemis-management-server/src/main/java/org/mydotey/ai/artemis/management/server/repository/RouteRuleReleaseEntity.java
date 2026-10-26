package org.mydotey.ai.artemis.management.server.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 发布记录表行映射（infrastructure，ADR-015）：领域 {@code RouteRuleRelease} 不带持久化
 * 注解；id 自增主键（insert 后回填）；snapshot 列存 targets JSON 数组字符串
 * （StoreImpl 序列化，V4/V5 先例）。
 */
@Data
@TableName("artemis_route_rule_release")
public class RouteRuleReleaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String namespace;

    private String service;

    private String ruleName;

    private String type;

    private String snapshot;

    private Long rollbackTo;

    private String operator;

    private Instant releasedAt;
}
