package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
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

    LeaseStatus leaseStatus(String namespace, String service);

    SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal);

    List<ConfigProperty> configStatus();

    /** 规则生效水位（phase-5）：当前版本 + 各活订阅方已应用版本 + 已生效节点数。 */
    RuleStatus ruleStatus();
}
