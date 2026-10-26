package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;

/**
 * 路由规则：服务内命名资源（唯一键 (namespace, service, name)），一组有序目标按权重分流。
 * 动作词汇 = 权重分配（不保留原产品 strategy：就近访问由宿主 RPC 框架执行，
 * 注册中心只表达切分意图）。operator/时间戳为管理面事实，由应用层填写。
 */
public record RouteRule(
        ServiceKey service,
        String name,
        RouteRuleStatus status,
        List<RouteRuleTarget> targets,
        String operator,
        Instant createdAt,
        Instant updatedAt) {}
