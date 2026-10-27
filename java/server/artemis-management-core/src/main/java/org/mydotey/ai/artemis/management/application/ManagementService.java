package org.mydotey.ai.artemis.management.application;

import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleRelease;
import org.mydotey.ai.artemis.management.domain.RuleStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 管理面用例（ADR-016）：有效视图 = registry 事实 + management 摘除记录。
 * 摘除为四级目标 + 操作记录即状态（v0.4 phase-3）。
 */
public interface ManagementService {

    /**
     * 服务列表（console 只读版）：keyword 大小写不敏感 substring 命中 namespace 或 service 任一，
     * 排序（namespace/service 升序）后过滤再切片；total = 过滤后总数；limit ≤ 0 = 全量。
     */
    ServicePage listServices(String namespace, String keyword, int offset, int limit);

    /** 服务详情：计数始终全量；offset/limit 只作用于可见实例集（includeEjected 先过滤后切片）。 */
    ServiceDetail getService(ServiceKey key, boolean includeEjected, int offset, int limit);

    /** 摘除（追加 EJECT，可叠加）；仅目标生效态翻转入 active 时推规则流增量。 */
    EjectionView eject(EjectionTarget target, String reason, String operator);

    /** 恢复（追加 RECOVER 对消）；无可对消记录幂等 no-op。仅翻转入 inactive 时推增量。 */
    void recover(EjectionTarget target, String operator);

    /** 摘除记录列表（时间倒序，最新在前，limit 截断）；activeOnly 只含未对消集；scope 可选过滤。 */
    List<EjectionView> listEjections(boolean activeOnly, EjectionScope scope, int limit);

    /** 审计查询（时间倒序 + operation 过滤 + limit）。 */
    List<AuditLog> auditLogs(String operation, int limit);

    /** 逻辑实例 upsert（全量覆盖，registry 入口同规则归一）；内容相同 → no-op，返回当前视图。 */
    LogicalInstance upsertLogicalInstance(LogicalInstance instance, String operator);

    /** 删除逻辑实例；缺失幂等 no-op。 */
    void deleteLogicalInstance(ServiceKey service, String instanceId, String operator);

    /** 逻辑实例列表（(namespace, service) 过滤可选，键升序，limit 截断）。 */
    List<LogicalInstance> listLogicalInstances(String namespace, String service, int limit);

    /** 创建路由规则（insert-if-absent，时间戳此处填写；生效权重归零——两段式初始未发布）；同名已存在 → ALREADY_EXISTS。 */
    RouteRule createRouteRule(RouteRule rule, String operator);

    /** 全量替换（createdAt 继承原值；生效权重按 selector 等值继承，编辑通道不触碰生效列）；缺失 → NOT_FOUND。 */
    RouteRule updateRouteRule(RouteRule rule, String operator);

    /** 删除；缺失幂等 no-op（不审计）。 */
    void deleteRouteRule(ServiceKey service, String name, String operator);

    /** 发布（一个 API 完成灰度意图）：targets 逐项 weight ← unreleasedWeight，落 append-only 发布记录并推规则流。 */
    RouteRule releaseRouteRule(ServiceKey service, String name, String operator);

    /** 一键回滚（指向历史版本的反向 release）：weight 与 unreleasedWeight 两列写回源快照，落 ROLLBACK 记录；源缺失/异规则 → RELEASE_NOT_FOUND。 */
    RouteRule rollbackRouteRule(ServiceKey service, String name, long releaseId, String operator);

    /** 发布差异预览：基 = 最新发布快照生效列（baseReleaseId null，未发布过 = 空基）或指定历史快照（回滚预览）；next = 当前编辑列（结构 + 权重全量变更）。 */
    ReleaseDiff routeRuleDiff(ServiceKey service, String name, Long baseReleaseId);

    /** 发布记录列表（(service, name) 内 id 降序，limit 截断）。 */
    List<RouteRuleRelease> listReleases(ServiceKey service, String name, int limit);

    /** 单条查询；缺失 → NOT_FOUND。 */
    RouteRule getRouteRule(ServiceKey service, String name);

    /** 列表（(namespace, service) 过滤可选，(namespace, service, name) 升序，limit 截断）。 */
    List<RouteRule> listRouteRules(String namespace, String service, int limit);

    /** canary 一键部署（route-rule + release 预置派生，架构 §5.2）：selector 圈金丝雀实例（非空
     * metadata 等值）、weight 0–10000 承接权重；派生保留名 canary 规则（canary 目标 + 兜底目标
     * 10000-weight，两列一致即已发布态），推规则流并落 RELEASE 记录（既有 rollback/releases 可用）。 */
    RouteRule deployCanary(ServiceKey service, Map<String, String> selector, int weight, String operator);

    /** 结束灰度：删除 canary 规则（无治理意图 = 全实例回归可见）；缺失幂等 no-op，发布记录保留。 */
    void deleteCanary(ServiceKey service, String operator);

    LeaseStatus leaseStatus(String namespace, String service);

    SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal);

    List<ConfigProperty> configStatus();

    /** 规则生效水位（phase-5）：当前版本 + 各活订阅方已应用版本 + 已生效节点数。 */
    RuleStatus ruleStatus();
}
