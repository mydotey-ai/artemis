package org.mydotey.ai.artemis.management.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionAckRegistry;
import org.mydotey.ai.artemis.management.domain.EjectionAction;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRecords;
import org.mydotey.ai.artemis.management.domain.EjectionRulePublisher;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstancePublisher;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceStore;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.domain.RuleStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;

/**
 * 有效视图组合：查询 registry 事实，叠加摘除记录（四级级联判定）；
 * 摘除/恢复只写 management 存储，不触达 registry。操作记录即状态：
 * EJECT 追加可叠加、RECOVER 追加对消，生效态翻转才推规则流增量
 * （at-most-once，分发异常不影响操作）。审计为摘要行、尽力而为
 * （状态源唯一在摘除记录表，架构 §5.3）。
 */
@Slf4j
public final class ManagementServiceImpl implements ManagementService {

    private static final Comparator<ServiceKey> SERVICE_ORDER =
            Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service);

    /** 「读-判-写」用例级互斥（对消计数竞态：并发 recover 会多对消一条）。
     * ponytail: 全局锁只服务管理操作级频率；按 target 锁分片当并发治理操作成规模时再做。 */
    private final Object ejectionLock = new Object();

    /** 逻辑实例读-判-写互斥（并发 upsert/delete 与推送成对串行，防流上乱序，ejectionLock 先例）。 */
    private final Object logicalInstanceLock = new Object();

    private static final Comparator<LogicalInstance> LOGICAL_ORDER = Comparator.comparing(
                    LogicalInstance::service, SERVICE_ORDER)
            .thenComparing(instance -> instance.instance().instanceId());

    private final RegistryQueryPort registry;
    private final EjectionStore ejections;
    private final AuditLogStore auditLogs;
    private final EjectionRulePublisher publisher;
    private final EjectionAckRegistry acks;
    private final LogicalInstanceStore logicalInstances;
    private final LogicalInstancePublisher logicalPublisher;
    private final Clock clock;

    public ManagementServiceImpl(
            RegistryQueryPort registry,
            EjectionStore ejections,
            AuditLogStore auditLogs,
            EjectionRulePublisher publisher,
            EjectionAckRegistry acks,
            LogicalInstanceStore logicalInstances,
            LogicalInstancePublisher logicalPublisher,
            Clock clock) {
        this.registry = registry;
        this.ejections = ejections;
        this.auditLogs = auditLogs;
        this.publisher = publisher;
        this.acks = acks;
        this.logicalInstances = logicalInstances;
        this.logicalPublisher = logicalPublisher;
        this.clock = clock;
    }

    @Override
    public List<ServiceView> listServices(String namespace) {
        // INSTANCE 级有效计数（selector 级不折算：列表无实例明细，精确视图走详情）
        Map<ServiceKey, Long> counts =
                EjectionRecords.activeInstanceCountByService(EjectionRecords.activeOf(ejections.findAll()));
        return registry.listServices(namespace).stream()
                .sorted(Comparator.comparing(ServiceSummary::key, SERVICE_ORDER))
                .map(summary -> new ServiceView(
                        summary.key(),
                        summary.instanceCount(),
                        counts.getOrDefault(summary.key(), 0L).intValue()))
                .toList();
    }

    @Override
    public ServiceDetail getService(ServiceKey key, boolean includeEjected) {
        List<Instance> instances = registry.getInstances(key);
        List<EjectionRecord> active = EjectionRecords.activeOf(ejections.findAll());

        List<InstanceView> views = instances.stream()
                .sorted(Comparator.comparing(Instance::instanceId))
                .map(instance -> InstanceView.of(instance, EjectionRecords.match(active, key, instance)))
                .toList();
        int ejectedCount = (int) views.stream().filter(InstanceView::ejected).count();
        List<InstanceView> visible = includeEjected
                ? views
                : views.stream().filter(view -> !view.ejected()).toList();

        return new ServiceDetail(key, instances.size(), ejectedCount, instances.size() - ejectedCount, visible);
    }

    @Override
    public EjectionView eject(EjectionTarget target, String reason, String operator) {
        EjectionRecord stored;
        // 推送在 ejectionLock 内：与「读-判-写」成对串行，否则并发同目标操作会把
        // onEjected/onRecovered 乱序送达 discovery（review #1），规则态与存储态分歧
        synchronized (ejectionLock) {
            boolean wasActive = EjectionRecords.isActive(ejections.findAll(), target);
            stored = ejections.append(
                    new EjectionRecord(target, EjectionAction.EJECT, reason, operator, clock.instant(), 0));
            if (!wasActive) {
                publisher.onEjected(stored); // 叠加（本已 active）不推：消费方无需变化
            }
        }
        audit(EjectionAction.EJECT.name(), target.describe(), operator, reason);
        return EjectionView.of(stored);
    }

    @Override
    public void recover(EjectionTarget target, String operator) {
        synchronized (ejectionLock) {
            if (!EjectionRecords.isActive(ejections.findAll(), target)) {
                return; // 无可对消记录：幂等 no-op（不追加不审计）
            }
            EjectionRecord stored = ejections.append(
                    new EjectionRecord(target, EjectionAction.RECOVER, null, operator, clock.instant(), 0));
            if (!EjectionRecords.isActive(ejections.findAll(), target)) {
                publisher.onRecovered(target, stored.sequence()); // 完全对消翻转入 inactive 才推（锁内，同 #1）
            }
        }
        audit(EjectionAction.RECOVER.name(), target.describe(), operator, null);
    }

    @Override
    public List<EjectionView> listEjections(boolean activeOnly, EjectionScope scope, int limit) {
        List<EjectionRecord> records = activeOnly ? EjectionRecords.activeOf(ejections.findAll()) : ejections.findAll();
        return records.stream()
                .filter(record -> scope == null || record.target().scope() == scope)
                .sorted(java.util.Comparator.comparingLong(EjectionRecord::sequence)
                        .reversed())
                .limit(Math.max(1, limit))
                .map(EjectionView::of)
                .toList();
    }

    @Override
    public List<AuditLog> auditLogs(String operation, int limit) {
        return auditLogs.find(operation, limit);
    }

    @Override
    public LogicalInstance upsertLogicalInstance(LogicalInstance instance, String operator) {
        LogicalInstance normalized = normalize(instance);
        ServiceKey service = normalized.service();
        String instanceId = normalized.instance().instanceId();
        // 推送在 logicalInstanceLock 内：与「读-判-写」成对串行，否则并发操作会把
        // onUpserted/onDeleted 乱序送达 discovery（同 ejectionLock 结论）
        synchronized (logicalInstanceLock) {
            LogicalInstance current = logicalInstances.find(service, instanceId);
            if (normalized.equals(current)) {
                return current; // 内容相同 no-op：不写库、不推送、不审计
            }
            logicalInstances.upsert(normalized);
            logicalPublisher.onUpserted(normalized);
        }
        audit(
                "LOGICAL_INSTANCE_UPSERT",
                describe(service, instanceId),
                operator,
                normalized.instance().host() + ":" + normalized.instance().port());
        return normalized;
    }

    @Override
    public void deleteLogicalInstance(ServiceKey service, String instanceId, String operator) {
        synchronized (logicalInstanceLock) {
            if (logicalInstances.find(service, instanceId) == null) {
                return; // 缺失幂等 no-op（不删除不审计不推送）
            }
            logicalInstances.delete(service, instanceId);
            logicalPublisher.onDeleted(service, instanceId);
        }
        audit("LOGICAL_INSTANCE_DELETE", describe(service, instanceId), operator, null);
    }

    @Override
    public List<LogicalInstance> listLogicalInstances(String namespace, String service, int limit) {
        return logicalInstances.findAll().stream()
                .filter(instance -> namespace == null
                        || namespace.isBlank()
                        || instance.service().namespace().equals(namespace))
                .filter(instance -> service == null
                        || service.isBlank()
                        || instance.service().service().equals(service))
                .sorted(LOGICAL_ORDER)
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public LeaseStatus leaseStatus(String namespace, String service) {
        return registry.leaseStatus(namespace, service);
    }

    @Override
    public SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal) {
        return registry.selfProtectionStatus(namespace, service, includeNormal);
    }

    @Override
    public List<ConfigProperty> configStatus() {
        return registry.configStatus();
    }

    @Override
    public RuleStatus ruleStatus() {
        long currentVersion = EjectionRecords.lastFlipSequence(ejections.findAll());
        List<RuleStatus.SubscriberWatermark> subscribers = acks.entries().stream()
                .map(entry -> new RuleStatus.SubscriberWatermark(
                        entry.subscriberId(),
                        entry.appliedVersion(),
                        entry.lastAckAt(),
                        Objects.equals(entry.appliedVersion(), currentVersion)))
                .toList();
        int appliedLatestCount = (int) subscribers.stream()
                .filter(RuleStatus.SubscriberWatermark::atLatest)
                .count();
        return new RuleStatus(currentVersion, subscribers, appliedLatestCount);
    }

    /** 审计摘要行：尽力而为（失败不阻塞治理操作，仅记录；状态源唯一在业务存储）。 */
    private void audit(String operation, String target, String operator, String detail) {
        try {
            auditLogs.append(new AuditLog(operation, target, operator, "SUCCESS", detail, clock.instant(), 0));
        } catch (RuntimeException e) {
            // ponytail: 摘要尽力而为；合规要求强一致审计时改事务模板包裹两表写入
            log.error("audit log append failed: operation={}, target={}", operation, target, e);
        }
    }

    /** registry 入口同规则归一：UNSPECIFIED 语义等同 UP；weight ≤ 0 归一为 100。 */
    private static LogicalInstance normalize(LogicalInstance instance) {
        Instance current = instance.instance();
        InstanceStatus status = current.status() == InstanceStatus.DOWN ? InstanceStatus.DOWN : InstanceStatus.UP;
        int weight = current.weight() <= 0 ? 100 : current.weight();
        return new LogicalInstance(
                instance.service(),
                new Instance(current.instanceId(), current.host(), current.port(), status, weight, current.metadata()));
    }

    private static String describe(ServiceKey service, String instanceId) {
        return service.namespace() + "/" + service.service() + "/" + instanceId;
    }
}
