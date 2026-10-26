package org.mydotey.ai.artemis.management.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleException;
import org.mydotey.ai.artemis.management.domain.RouteRuleStore;
import org.mydotey.ai.artemis.management.domain.RouteRules;
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

    private static final Comparator<RouteRule> ROUTE_RULE_ORDER =
            Comparator.comparing(RouteRule::service, SERVICE_ORDER).thenComparing(RouteRule::name);

    private final RegistryQueryPort registry;
    private final EjectionStore ejections;
    private final AuditLogStore auditLogs;
    private final EjectionRulePublisher publisher;
    private final EjectionAckRegistry acks;
    private final LogicalInstanceStore logicalInstances;
    private final LogicalInstancePublisher logicalPublisher;
    private final RouteRuleStore routeRules;
    private final Clock clock;

    public ManagementServiceImpl(
            RegistryQueryPort registry,
            EjectionStore ejections,
            AuditLogStore auditLogs,
            EjectionRulePublisher publisher,
            EjectionAckRegistry acks,
            LogicalInstanceStore logicalInstances,
            LogicalInstancePublisher logicalPublisher,
            RouteRuleStore routeRules,
            Clock clock) {
        this.registry = registry;
        this.ejections = ejections;
        this.auditLogs = auditLogs;
        this.publisher = publisher;
        this.acks = acks;
        this.logicalInstances = logicalInstances;
        this.logicalPublisher = logicalPublisher;
        this.routeRules = routeRules;
        this.clock = clock;
    }

    @Override
    public ServicePage listServices(String namespace, String keyword, int offset, int limit) {
        // INSTANCE 级有效计数（selector 级不折算：列表无实例明细，精确视图走详情）
        Map<ServiceKey, Long> counts =
                EjectionRecords.activeInstanceCountByService(EjectionRecords.activeOf(ejections.findAll()));
        List<ServiceView> views = registry.listServices(namespace).stream()
                .sorted(Comparator.comparing(ServiceSummary::key, SERVICE_ORDER))
                .map(summary -> new ServiceView(
                        summary.key(),
                        summary.instanceCount(),
                        counts.getOrDefault(summary.key(), 0L).intValue()))
                .toList();
        // 排序确定 → 过滤 → total → 切片，分页页间稳定
        List<ServiceView> filtered = keyword == null || keyword.isBlank()
                ? views
                : views.stream()
                        .filter(view -> matchesKeyword(view.key(), keyword))
                        .toList();
        return new ServicePage(slice(filtered, offset, limit), filtered.size());
    }

    @Override
    public ServiceDetail getService(ServiceKey key, boolean includeEjected, int offset, int limit) {
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

        return new ServiceDetail(
                key, instances.size(), ejectedCount, instances.size() - ejectedCount, slice(visible, offset, limit));
    }

    private static boolean matchesKeyword(ServiceKey key, String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        return key.namespace().toLowerCase(Locale.ROOT).contains(needle)
                || key.service().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** limit ≤ 0 = 全量；offset 负数归零，越界为空页。全量路径零复制（调用方入参均为不可变列表）。 */
    private static <T> List<T> slice(List<T> list, int offset, int limit) {
        int from = Math.min(Math.max(offset, 0), list.size());
        int to = limit <= 0 ? list.size() : Math.min(from + limit, list.size());
        return from == 0 && to == list.size() ? list : List.copyOf(list.subList(from, to));
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
    public RouteRule createRouteRule(RouteRule rule, String operator) {
        RouteRules.validate(rule);
        Instant now = clock.instant();
        RouteRule stored =
                new RouteRule(rule.service(), rule.name(), rule.status(), rule.targets(), operator, now, now);
        if (!routeRules.insert(stored)) {
            throw new RouteRuleException(
                    RouteRuleException.ALREADY_EXISTS, "route rule exists: " + describe(rule.service(), rule.name()));
        }
        audit("CREATE_ROUTE_RULE", routeRuleTarget(rule.service(), rule.name()), operator, targetsSummary(stored));
        return stored;
    }

    @Override
    public RouteRule updateRouteRule(RouteRule rule, String operator) {
        RouteRules.validate(rule);
        RouteRule current = routeRules.find(rule.service(), rule.name());
        if (current == null) {
            throw notFound(rule.service(), rule.name());
        }
        // createdAt 继承原值（创建事实不随替换漂移）；updatedAt 刷新
        RouteRule stored = new RouteRule(
                rule.service(),
                rule.name(),
                rule.status(),
                rule.targets(),
                operator,
                current.createdAt(),
                clock.instant());
        if (!routeRules.update(stored)) {
            throw notFound(rule.service(), rule.name()); // find 后被并发删除
        }
        audit("UPDATE_ROUTE_RULE", routeRuleTarget(rule.service(), rule.name()), operator, targetsSummary(stored));
        return stored;
    }

    @Override
    public void deleteRouteRule(ServiceKey service, String name, String operator) {
        if (!routeRules.delete(service, name)) {
            return; // 缺失幂等 no-op（不删除不审计）
        }
        audit("DELETE_ROUTE_RULE", routeRuleTarget(service, name), operator, null);
    }

    @Override
    public RouteRule getRouteRule(ServiceKey service, String name) {
        RouteRule rule = routeRules.find(service, name);
        if (rule == null) {
            throw notFound(service, name);
        }
        return rule;
    }

    @Override
    public List<RouteRule> listRouteRules(String namespace, String service, int limit) {
        return routeRules.findAll().stream()
                .filter(rule -> namespace == null
                        || namespace.isBlank()
                        || rule.service().namespace().equals(namespace))
                .filter(rule -> service == null
                        || service.isBlank()
                        || rule.service().service().equals(service))
                .sorted(ROUTE_RULE_ORDER)
                .limit(Math.max(1, limit))
                .toList();
    }

    private static RouteRuleException notFound(ServiceKey service, String name) {
        return new RouteRuleException(RouteRuleException.NOT_FOUND, "route rule not found: " + describe(service, name));
    }

    /** 审计 target：资源描述串（带资源前缀，同 ejection 摘要行先例）。 */
    private static String routeRuleTarget(ServiceKey service, String name) {
        return "route-rule:" + describe(service, name);
    }

    /** 审计 detail：目标条数 + weight 序列（release 语义 phase-2，此处记生效列）。 */
    private static String targetsSummary(RouteRule rule) {
        String weights = rule.targets().stream()
                .map(target -> Integer.toString(target.weight()))
                .collect(Collectors.joining(","));
        return "targets=" + rule.targets().size() + " weights=[" + weights + "]";
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
