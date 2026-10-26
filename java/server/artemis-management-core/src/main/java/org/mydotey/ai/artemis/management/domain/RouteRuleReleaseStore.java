package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 发布记录存储端口：append-only（无更新/删除——记录即历史，规则删除后记录保留，
 * 审计语义）。id 由 store 分配（append 入参 id 忽略），时间戳由应用层填写。
 */
public interface RouteRuleReleaseStore {

    /** append（id 由实现分配）；返回带 id 的落库记录。 */
    RouteRuleRelease append(RouteRuleRelease release);

    /** 单条查询（回滚源校验）；缺失返回 null。 */
    RouteRuleRelease find(ServiceKey service, String name, long id);

    /** (service, name) 内 id 降序（最新在前），limit 截断。 */
    List<RouteRuleRelease> list(ServiceKey service, String name, int limit);
}
