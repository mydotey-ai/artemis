package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 路由规则存储端口：只存取，资源状态判定（已存在/缺失）由布尔返回值表达，
 * 用例层翻译为域异常。时间戳由应用层填写（两态一致），实现照存。
 */
public interface RouteRuleStore {

    RouteRule find(ServiceKey service, String name);

    List<RouteRule> findAll();

    /** insert-if-absent；已存在返回 false（无写入）。 */
    boolean insert(RouteRule rule);

    /** 全量替换（rule 内含定位键与继承的 createdAt）；缺失返回 false（无写入）。 */
    boolean update(RouteRule rule);

    /** 删除；缺失返回 false（幂等）。 */
    boolean delete(ServiceKey service, String name);
}
