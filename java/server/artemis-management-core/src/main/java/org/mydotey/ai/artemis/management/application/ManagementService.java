package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 管理面用例（ADR-016）：有效视图 = registry 事实 + management 摘除记录。
 * 摘除为四级目标 + 操作记录即状态（v0.4 phase-3）。
 */
public interface ManagementService {

    List<ServiceView> listServices(String namespace);

    ServiceDetail getService(ServiceKey key, boolean includeEjected);

    /** 摘除（追加 EJECT，可叠加）；仅目标生效态翻转入 active 时推规则流增量。 */
    EjectionView eject(EjectionTarget target, String reason, String operator);

    /** 恢复（追加 RECOVER 对消）；无可对消记录幂等 no-op。仅翻转入 inactive 时推增量。 */
    void recover(EjectionTarget target, String operator);

    /** 摘除记录列表（时间倒序，最新在前，limit 截断）；activeOnly 只含未对消集；scope 可选过滤。 */
    List<EjectionView> listEjections(boolean activeOnly, EjectionScope scope, int limit);

    /** 审计查询（时间倒序 + operation 过滤 + limit）。 */
    List<AuditLog> auditLogs(String operation, int limit);

    LeaseStatus leaseStatus(String namespace, String service);

    SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal);

    List<ConfigProperty> configStatus();
}
