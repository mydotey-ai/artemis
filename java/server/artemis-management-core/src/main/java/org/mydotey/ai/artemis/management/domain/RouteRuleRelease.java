package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;

/**
 * 发布记录（资源 release，架构 §5.2）：append-only，快照 = 发布/回滚后的完整 targets
 * （两列）——回滚历史源由本记录承载（规则表始终当前态，phase-1 版本化决策）。
 * id 为全局单调序（DB 自增主键 / 内存计数器），资源寻址用；规则流版本与此独立
 * （dispatcher tick，见 RouteRuleDispatcher）。
 */
public record RouteRuleRelease(
        long id,
        ServiceKey service,
        String name,
        ReleaseType type,
        List<RouteRuleTarget> snapshot,
        Long rollbackTo,
        String operator,
        Instant releasedAt) {

    public RouteRuleRelease {
        snapshot = List.copyOf(snapshot);
    }
}
