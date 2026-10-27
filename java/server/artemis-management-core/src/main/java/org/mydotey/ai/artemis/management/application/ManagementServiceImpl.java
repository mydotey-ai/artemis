package org.mydotey.ai.artemis.management.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
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
import org.mydotey.ai.artemis.management.domain.ReleaseType;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleException;
import org.mydotey.ai.artemis.management.domain.RouteRulePublisher;
import org.mydotey.ai.artemis.management.domain.RouteRuleRelease;
import org.mydotey.ai.artemis.management.domain.RouteRuleReleaseStore;
import org.mydotey.ai.artemis.management.domain.RouteRuleStatus;
import org.mydotey.ai.artemis.management.domain.RouteRuleStore;
import org.mydotey.ai.artemis.management.domain.RouteRuleTarget;
import org.mydotey.ai.artemis.management.domain.RouteRules;
import org.mydotey.ai.artemis.management.domain.RuleAckRegistry;
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

    /** 路由规则读-判-写（update 权重继承 / release 拷贝）与推送成对串行，防流上乱序（同先例）。 */
    private final Object routeRuleLock = new Object();

    private static final Comparator<LogicalInstance> LOGICAL_ORDER = Comparator.comparing(
                    LogicalInstance::service, SERVICE_ORDER)
            .thenComparing(instance -> instance.instance().instanceId());

    private static final Comparator<RouteRule> ROUTE_RULE_ORDER =
            Comparator.comparing(RouteRule::service, SERVICE_ORDER).thenComparing(RouteRule::name);

    private final RegistryQueryPort registry;
    private final EjectionStore ejections;
    private final AuditLogStore auditLogs;
    private final EjectionRulePublisher publisher;
    private final RuleAckRegistry acks;
    private final LogicalInstanceStore logicalInstances;
    private final LogicalInstancePublisher logicalPublisher;
    private final RouteRuleStore routeRules;
    private final RouteRuleReleaseStore releases;
    private final RouteRulePublisher routeRulePublisher;
    private final RuleAckRegistry routeRuleAcks;
    private final Clock clock;

    public ManagementServiceImpl(
            RegistryQueryPort registry,
            EjectionStore ejections,
            AuditLogStore auditLogs,
            EjectionRulePublisher publisher,
            RuleAckRegistry acks,
            LogicalInstanceStore logicalInstances,
            LogicalInstancePublisher logicalPublisher,
            RouteRuleStore routeRules,
            RouteRuleReleaseStore releases,
            RouteRulePublisher routeRulePublisher,
            RuleAckRegistry routeRuleAcks,
            Clock clock) {
        this.registry = registry;
        this.ejections = ejections;
        this.auditLogs = auditLogs;
        this.publisher = publisher;
        this.acks = acks;
        this.logicalInstances = logicalInstances;
        this.logicalPublisher = logicalPublisher;
        this.routeRules = routeRules;
        this.releases = releases;
        this.routeRulePublisher = routeRulePublisher;
        this.routeRuleAcks = routeRuleAcks;
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
        // 两段式不变量：生效值列初始归零（创建 = 定义结构，release 才生效）
        RouteRule stored = new RouteRule(
                rule.service(),
                rule.name(),
                rule.status(),
                rule.targets().stream()
                        .map(target -> new RouteRuleTarget(target.selector(), 0, target.unreleasedWeight()))
                        .toList(),
                operator,
                now,
                now);
        synchronized (routeRuleLock) {
            if (!routeRules.insert(stored)) {
                throw new RouteRuleException(
                        RouteRuleException.ALREADY_EXISTS,
                        "route rule exists: " + describe(rule.service(), rule.name()));
            }
            notifyRuleChanged(stored);
        }
        audit("CREATE_ROUTE_RULE", routeRuleTarget(rule.service(), rule.name()), operator, targetsSummary(stored));
        return stored;
    }

    @Override
    public RouteRule updateRouteRule(RouteRule rule, String operator) {
        RouteRules.validate(rule);
        RouteRule stored;
        synchronized (routeRuleLock) {
            RouteRule current = routeRules.find(rule.service(), rule.name());
            if (current == null) {
                throw notFound(rule.service(), rule.name());
            }
            // createdAt 继承原值（创建事实不随替换漂移）；updatedAt 刷新；
            // 生效权重按 selector 等值继承——编辑通道不触碰生效值列（两段式不变量）
            RouteRule replacement = new RouteRule(
                    rule.service(),
                    rule.name(),
                    rule.status(),
                    inheritWeights(current.targets(), rule.targets()),
                    operator,
                    current.createdAt(),
                    clock.instant());
            if (!routeRules.update(replacement)) {
                throw notFound(rule.service(), rule.name()); // find 后被并发删除
            }
            stored = replacement;
            notifyRuleChanged(stored);
        }
        audit("UPDATE_ROUTE_RULE", routeRuleTarget(rule.service(), rule.name()), operator, targetsSummary(stored));
        return stored;
    }

    @Override
    public void deleteRouteRule(ServiceKey service, String name, String operator) {
        synchronized (routeRuleLock) {
            if (!routeRules.delete(service, name)) {
                return; // 缺失幂等 no-op（不删除不审计）
            }
            routeRulePublisher.onDeleted(service, name);
        }
        audit("DELETE_ROUTE_RULE", routeRuleTarget(service, name), operator, null);
    }

    @Override
    public RouteRule releaseRouteRule(ServiceKey service, String name, String operator) {
        Instant now = clock.instant();
        RouteRule released;
        RouteRuleRelease record;
        synchronized (routeRuleLock) {
            RouteRule current = routeRules.find(service, name);
            if (current == null) {
                throw notFound(service, name);
            }
            // 拷贝语义：逐 target weight ← unreleasedWeight（两列已相等照常落记录——显式动作，审计需要）
            List<RouteRuleTarget> copied = current.targets().stream()
                    .map(target -> new RouteRuleTarget(
                            target.selector(), target.unreleasedWeight(), target.unreleasedWeight()))
                    .toList();
            released = new RouteRule(service, name, current.status(), copied, operator, current.createdAt(), now);
            if (!routeRules.update(released)) {
                throw notFound(service, name); // find 后被并发删除
            }
            // 生效优先：流与存储一致先于发布记录（append 失败 → 503，重试 release 幂等补录记录，
            // 重试再推一次 upserted 消费方覆盖语义幂等无害；§8 两写边界）
            notifyRuleChanged(released);
            record = releases.append(
                    new RouteRuleRelease(0, service, name, ReleaseType.RELEASE, copied, null, operator, now));
        }
        audit(
                "RELEASE_ROUTE_RULE",
                routeRuleTarget(service, name),
                operator,
                targetsSummary(released) + " releaseId=" + record.id());
        return released;
    }

    @Override
    public RouteRule rollbackRouteRule(ServiceKey service, String name, long releaseId, String operator) {
        Instant now = clock.instant();
        RouteRule restored;
        synchronized (routeRuleLock) {
            RouteRule current = routeRules.find(service, name);
            if (current == null) {
                throw notFound(service, name);
            }
            RouteRuleRelease source = releases.find(service, name, releaseId);
            if (source == null) {
                throw new RouteRuleException(
                        RouteRuleException.RELEASE_NOT_FOUND,
                        "release not found: " + describe(service, name) + "/" + releaseId);
            }
            // 反向 release：weight 与 unreleasedWeight 两列写回源快照（完全恢复源发布时刻状态）
            restored = new RouteRule(
                    service, name, current.status(), source.snapshot(), operator, current.createdAt(), now);
            if (!routeRules.update(restored)) {
                throw notFound(service, name);
            }
            notifyRuleChanged(restored); // 生效优先（同 release 写序）
            releases.append(new RouteRuleRelease(
                    0, service, name, ReleaseType.ROLLBACK, source.snapshot(), releaseId, operator, now));
        }
        audit(
                "ROLLBACK_ROUTE_RULE",
                routeRuleTarget(service, name),
                operator,
                "rollbackTo=" + releaseId + " " + targetsSummary(restored));
        return restored;
    }

    @Override
    public ReleaseDiff routeRuleDiff(ServiceKey service, String name, Long baseReleaseId) {
        RouteRule rule = routeRules.find(service, name);
        if (rule == null) {
            throw notFound(service, name);
        }
        // 基 = 发布快照生效列：缺省取最新发布记录（未发布过 = 空基，全量新增）；
        // 指定 releaseId 取该记录（回滚预览）。next = 当前编辑列（结构 + 权重全量变更）。
        RouteRuleRelease base;
        if (baseReleaseId == null) {
            List<RouteRuleRelease> latest = releases.list(service, name, 1);
            base = latest.isEmpty() ? null : latest.get(0);
        } else {
            base = releases.find(service, name, baseReleaseId);
            if (base == null) {
                throw new RouteRuleException(
                        RouteRuleException.RELEASE_NOT_FOUND,
                        "release not found: " + describe(service, name) + "/" + baseReleaseId);
            }
        }
        List<RouteRuleTarget> baseTargets = base == null ? List.of() : base.snapshot();
        return new ReleaseDiff(service, name, baseReleaseId, diffTargets(baseTargets, rule.targets()));
    }

    @Override
    public List<RouteRuleRelease> listReleases(ServiceKey service, String name, int limit) {
        return releases.list(service, name, Math.max(1, limit));
    }

    /** 生效权重按 selector 等值继承（重复 selector 已被域校验拒绝，匹配唯一）。 */
    private static List<RouteRuleTarget> inheritWeights(List<RouteRuleTarget> current, List<RouteRuleTarget> next) {
        Map<Map<String, String>, Integer> weights = new HashMap<>();
        for (RouteRuleTarget target : current) {
            weights.put(target.selector(), target.weight());
        }
        return next.stream()
                .map(target -> new RouteRuleTarget(
                        target.selector(), weights.getOrDefault(target.selector(), 0), target.unreleasedWeight()))
                .toList();
    }

    /** diff 并集对齐（selector 等值）：基 = 发布快照生效列、next = 当前编辑列；
     * 编辑序为主、基独有（将被移除）追加尾部。 */
    private static List<ReleaseDiff.TargetDiff> diffTargets(List<RouteRuleTarget> base, List<RouteRuleTarget> next) {
        Map<Map<String, String>, Integer> baseWeights = new HashMap<>();
        for (RouteRuleTarget target : base) {
            baseWeights.put(target.selector(), target.weight());
        }
        List<ReleaseDiff.TargetDiff> diffs = new ArrayList<>(next.size());
        Set<Map<String, String>> nextSelectors = new HashSet<>(next.size());
        for (RouteRuleTarget target : next) {
            diffs.add(new ReleaseDiff.TargetDiff(
                    target.selector(), baseWeights.get(target.selector()), target.unreleasedWeight()));
            nextSelectors.add(target.selector());
        }
        for (RouteRuleTarget target : base) {
            if (!nextSelectors.contains(target.selector())) {
                diffs.add(new ReleaseDiff.TargetDiff(target.selector(), target.weight(), null));
            }
        }
        return diffs;
    }

    /** 规则变更通知（流内容 = ACTIVE 集的判定收敛在 dispatcher 单点，见 RouteRuleDispatcher）。 */
    private void notifyRuleChanged(RouteRule rule) {
        routeRulePublisher.onUpserted(rule);
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

    // ---- canary（v0.5 phase-4：route-rule + release 预置派生，架构 §5.2）----

    @Override
    public RouteRule deployCanary(ServiceKey service, Map<String, String> selector, int weight, String operator) {
        Map<String, String> canarySelector = selector == null ? Map.of() : selector;
        // 空 selector 先拒：空 map 在规则域是兜底目标语义，canary 圈定必须非空（validate 放行空 map）
        if (canarySelector.isEmpty()) {
            throw new IllegalArgumentException("canary selector required (empty selector is fallback-only)");
        }
        // 派生规则：canary 目标 + 兜底目标，两列一致 = 已发布态（deploy 本身就是一次 release，
        // 落 RELEASE 记录 → 既有 rollback / releases / diff 对 canary 规则直接可用）
        List<RouteRuleTarget> targets = List.of(
                new RouteRuleTarget(canarySelector, weight, weight),
                new RouteRuleTarget(Map.of(), RouteRules.MAX_WEIGHT - weight, RouteRules.MAX_WEIGHT - weight));
        RouteRules.validate(
                new RouteRule(
                        service,
                        RouteRules.RESERVED_NAME_CANARY,
                        RouteRuleStatus.ACTIVE,
                        targets,
                        operator,
                        null,
                        null),
                true); // 保留名独占：canary 派生通道放行
        Instant now = clock.instant();
        RouteRule stored;
        RouteRuleRelease record;
        synchronized (routeRuleLock) {
            RouteRule current = routeRules.find(service, RouteRules.RESERVED_NAME_CANARY);
            // 全量替换派生 targets（单次构造：createdAt 继承创建事实，首次部署 = now）；
            // 重圈定（selector 变更）时旧 canary 组自然回归兜底承接
            stored = new RouteRule(
                    service,
                    RouteRules.RESERVED_NAME_CANARY,
                    RouteRuleStatus.ACTIVE,
                    targets,
                    operator,
                    current == null ? now : current.createdAt(),
                    now);
            boolean written = current == null ? routeRules.insert(stored) : routeRules.update(stored);
            if (!written) {
                // find 后被并发删除（update）/ insert 撞唯一键（insert）——锁内不可能，防御兜底
                throw current == null
                        ? new RouteRuleException(
                                RouteRuleException.ALREADY_EXISTS,
                                "route rule exists: " + describe(service, RouteRules.RESERVED_NAME_CANARY))
                        : notFound(service, RouteRules.RESERVED_NAME_CANARY);
            }
            // 生效优先：流与存储一致先于发布记录（append 失败 → 503，重试 deploy 幂等补录；
            // 两列已一致照常落记录，release 写序同型）
            notifyRuleChanged(stored);
            record = releases.append(new RouteRuleRelease(
                    0, service, RouteRules.RESERVED_NAME_CANARY, ReleaseType.RELEASE, targets, null, operator, now));
        }
        audit(
                "DEPLOY_CANARY",
                routeRuleTarget(service, RouteRules.RESERVED_NAME_CANARY),
                operator,
                "selector=" + canarySelector + " weight=" + weight + " releaseId=" + record.id());
        return stored;
    }

    @Override
    public void deleteCanary(ServiceKey service, String operator) {
        synchronized (routeRuleLock) {
            if (!routeRules.delete(service, RouteRules.RESERVED_NAME_CANARY)) {
                return; // 缺失幂等 no-op（不删除不审计）；发布记录 append-only 保留
            }
            routeRulePublisher.onDeleted(service, RouteRules.RESERVED_NAME_CANARY);
        }
        audit("DELETE_CANARY", routeRuleTarget(service, RouteRules.RESERVED_NAME_CANARY), operator, null);
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
        List<RuleStatus.SubscriberWatermark> subscribers = watermarks(acks, currentVersion);
        return new RuleStatus(currentVersion, subscribers, appliedLatestCount(subscribers), routeRulesStatus());
    }

    /** 路由规则流水位节（phase-2，与摘除流同构、独立版本域：currentVersion = dispatcher tick）。 */
    private RuleStatus.RouteRulesStatus routeRulesStatus() {
        long currentVersion = routeRulePublisher.version();
        List<RuleStatus.SubscriberWatermark> subscribers = watermarks(routeRuleAcks, currentVersion);
        return new RuleStatus.RouteRulesStatus(currentVersion, subscribers, appliedLatestCount(subscribers));
    }

    private static List<RuleStatus.SubscriberWatermark> watermarks(RuleAckRegistry acks, long currentVersion) {
        return acks.entries().stream()
                .map(entry -> new RuleStatus.SubscriberWatermark(
                        entry.subscriberId(),
                        entry.appliedVersion(),
                        entry.lastAckAt(),
                        Objects.equals(entry.appliedVersion(), currentVersion)))
                .toList();
    }

    private static int appliedLatestCount(List<RuleStatus.SubscriberWatermark> subscribers) {
        return (int) subscribers.stream()
                .filter(RuleStatus.SubscriberWatermark::atLatest)
                .count();
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
