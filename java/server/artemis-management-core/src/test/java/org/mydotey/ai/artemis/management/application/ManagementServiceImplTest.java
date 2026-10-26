package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionAckRegistry;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseDetail;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStats;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;
import org.mydotey.ai.artemis.management.infrastructure.EjectionRuleDispatcher;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryAuditLogStore;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryEjectionStore;
import org.mydotey.ai.artemis.management.infrastructure.InMemoryLogicalInstanceStore;
import org.mydotey.ai.artemis.management.infrastructure.LogicalInstanceDispatcher;

class ManagementServiceImplTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final String OPERATOR = "admin";

    private final FakeRegistry registry = new FakeRegistry();
    private final InMemoryEjectionStore ejections = new InMemoryEjectionStore();
    private final InMemoryAuditLogStore auditLogs = new InMemoryAuditLogStore();
    private final EjectionRuleDispatcher dispatcher = new EjectionRuleDispatcher(ejections);
    private final InMemoryLogicalInstanceStore logicalInstances = new InMemoryLogicalInstanceStore();
    private final LogicalInstanceDispatcher logicalDispatcher = new LogicalInstanceDispatcher(logicalInstances);
    private final EjectionAckRegistry acks = new EjectionAckRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ManagementServiceImpl service = new ManagementServiceImpl(
            registry,
            ejections,
            auditLogs,
            dispatcher,
            acks,
            logicalInstances,
            logicalDispatcher,
            Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 视图组合 ----

    @Test
    void should_filter_ejected_instances_when_get_service() {
        registry.instances.put(SERVICE, List.of(instance("i1"), instance("i2")));
        service.eject(target("i1"), "maintenance", OPERATOR);

        ServiceDetail detail = service.getService(SERVICE, false, 0, 0);

        assertThat(detail.instanceCount()).isEqualTo(2);
        assertThat(detail.ejectedCount()).isEqualTo(1);
        assertThat(detail.effectiveInstanceCount()).isEqualTo(1);
        assertThat(detail.instances())
                .singleElement()
                .extracting(InstanceView::instanceId)
                .isEqualTo("i2");
    }

    @Test
    void should_include_ejected_instances_when_flag_set() {
        registry.instances.put(SERVICE, List.of(instance("i1"), instance("i2")));
        service.eject(target("i1"), "maintenance", OPERATOR);

        ServiceDetail detail = service.getService(SERVICE, true, 0, 0);

        assertThat(detail.instances()).hasSize(2);
        assertThat(detail.instances().get(0).ejected()).isTrue();
        assertThat(detail.instances().get(0).reason()).isEqualTo("maintenance");
        assertThat(detail.instances().get(0).ejectedAt()).isEqualTo(NOW);
        assertThat(detail.instances().get(1).ejected()).isFalse();
    }

    @Test
    void should_return_zero_counts_when_service_not_registered() {
        ServiceDetail detail = service.getService(SERVICE, false, 0, 0);

        assertThat(detail.instanceCount()).isZero();
        assertThat(detail.ejectedCount()).isZero();
        assertThat(detail.effectiveInstanceCount()).isZero();
        assertThat(detail.instances()).isEmpty();
    }

    @Test
    void should_count_only_registered_ejections_when_get_service() {
        registry.instances.put(SERVICE, List.of(instance("i1")));
        service.eject(target("i1"), "r1", OPERATOR);
        service.eject(target("stale"), "r2", OPERATOR);

        ServiceDetail detail = service.getService(SERVICE, false, 0, 0);

        assertThat(detail.ejectedCount()).isEqualTo(1);
        assertThat(detail.effectiveInstanceCount()).isZero();
    }

    @Test
    void should_list_services_with_fact_and_ejection_counts() {
        registry.services.add(new ServiceSummary(SERVICE, 2));
        registry.services.add(new ServiceSummary(new ServiceKey("ns", "another"), 1));
        service.eject(target("i1"), "r1", OPERATOR);

        List<ServiceView> views = service.listServices("ns", null, 0, 0).services();

        assertThat(views)
                .extracting(ServiceView::key, ServiceView::instanceCount, ServiceView::ejectedCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(new ServiceKey("ns", "another"), 1, 0),
                        org.assertj.core.groups.Tuple.tuple(SERVICE, 2, 1));
    }

    @Test
    void should_list_services_counting_instance_scope_only() {
        registry.services.add(new ServiceSummary(SERVICE, 2));
        // selector 级摘除不折算进服务列表计数（列表无实例明细，精确视图走详情）
        service.eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), "zone down", OPERATOR);

        assertThat(service.listServices("ns", null, 0, 0).services().get(0).ejectedCount())
                .isZero();
    }

    // ---- console 只读版：搜索 + 分页（v0.4 phase-6）----

    @Test
    void should_filter_services_when_keyword_matched_case_insensitive() {
        registry.services.add(new ServiceSummary(SERVICE, 1)); // ns/svc
        registry.services.add(new ServiceSummary(new ServiceKey("prod", "Order-Service"), 1));
        registry.services.add(new ServiceSummary(new ServiceKey("prod", "user-service"), 1));

        ServicePage byService = service.listServices(null, "ORDER", 0, 0);

        // 命中 service 名（大小写不敏感），未命中的排除
        assertThat(byService.services())
                .extracting(view -> view.key().namespace() + "/" + view.key().service())
                .containsExactly("prod/Order-Service");
        assertThat(byService.total()).isEqualTo(1);

        ServicePage byNamespace = service.listServices(null, "PROD", 0, 0);

        // 命中 namespace：两服务均保留且按 SERVICE_ORDER 排序
        assertThat(byNamespace.services())
                .extracting(view -> view.key().namespace() + "/" + view.key().service())
                .containsExactly("prod/Order-Service", "prod/user-service");
        assertThat(byNamespace.total()).isEqualTo(2);
    }

    @Test
    void should_keep_full_list_when_no_keyword_and_no_limit() {
        registry.services.add(new ServiceSummary(SERVICE, 1));
        registry.services.add(new ServiceSummary(new ServiceKey("ns", "another"), 1));

        ServicePage page = service.listServices("ns", null, 0, 0);

        assertThat(page.services()).hasSize(2); // 既有全量行为不变（additive 兼容）
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void should_page_services_with_total_when_offset_limit_given() {
        for (int i = 1; i <= 5; i++) {
            registry.services.add(new ServiceSummary(new ServiceKey("ns", "svc-" + i), i));
        }

        ServicePage first = service.listServices(null, "svc-", 0, 2);
        ServicePage second = service.listServices(null, "svc-", 2, 2);
        ServicePage last = service.listServices(null, "svc-", 4, 2);

        assertThat(first.services()).extracting(view -> view.key().service()).containsExactly("svc-1", "svc-2");
        assertThat(second.services()).extracting(view -> view.key().service()).containsExactly("svc-3", "svc-4");
        assertThat(last.services()).extracting(view -> view.key().service()).containsExactly("svc-5");
        assertThat(first.total()).isEqualTo(5); // total = 过滤后总数，页间不重不漏
        assertThat(second.total()).isEqualTo(5);
        assertThat(last.total()).isEqualTo(5);
    }

    @Test
    void should_return_empty_page_when_offset_beyond_total() {
        registry.services.add(new ServiceSummary(SERVICE, 1));

        ServicePage page = service.listServices(null, null, 10, 10);

        assertThat(page.services()).isEmpty();
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void should_bound_page_size_when_large_service_set_constructed() {
        for (int i = 0; i < 5000; i++) {
            registry.services.add(new ServiceSummary(new ServiceKey("ns", "svc-" + String.format("%04d", i)), 1));
        }

        ServicePage page = service.listServices(null, null, 4995, 10);

        assertThat(page.services()).hasSize(5); // 尾页只含剩余项
        assertThat(page.total()).isEqualTo(5000);
    }

    @Test
    void should_page_instance_views_when_detail_offset_limit_given() {
        registry.instances.put(SERVICE, List.of(instance("i1"), instance("i2"), instance("i3")));
        service.eject(target("i1"), "r", OPERATOR);

        ServiceDetail detail = service.getService(SERVICE, true, 1, 1);

        // counts 始终全量，instances 切片（includeEjected 先过滤后切片）
        assertThat(detail.instanceCount()).isEqualTo(3);
        assertThat(detail.ejectedCount()).isEqualTo(1);
        assertThat(detail.instances())
                .singleElement()
                .extracting(InstanceView::instanceId)
                .isEqualTo("i2");
        assertThat(service.getService(SERVICE, false, 0, 1).instances())
                .singleElement()
                .extracting(InstanceView::instanceId)
                .isEqualTo("i2"); // 摘除过滤后首片
    }

    // ---- 四级级联命中 ----

    @Test
    void should_annotate_instance_by_selector_metadata_match() {
        registry.instances.put(
                SERVICE, List.of(instance("i1", Map.of("zone", "dc-2")), instance("i2", Map.of("zone", "dc-1"))));
        service.eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), "zone down", OPERATOR);

        ServiceDetail detail = service.getService(SERVICE, true, 0, 0);

        assertThat(detail.instances())
                .extracting(InstanceView::instanceId, InstanceView::ejected)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("i1", true),
                        org.assertj.core.groups.Tuple.tuple("i2", false));
        assertThat(detail.instances().get(0).reason()).isEqualTo("zone down");
        assertThat(detail.effectiveInstanceCount()).isEqualTo(1);
    }

    @Test
    void should_match_selector_across_services_globally() {
        ServiceKey other = new ServiceKey("ns", "other");
        registry.instances.put(SERVICE, List.of(instance("i1", Map.of("server", "10.1.1.1"))));
        registry.instances.put(other, List.of(instance("x1", Map.of("server", "10.1.1.1"))));
        service.eject(EjectionTarget.selector(EjectionScope.SERVER, "10.1.1.1"), "host down", OPERATOR);

        assertThat(service.getService(SERVICE, true, 0, 0).ejectedCount()).isEqualTo(1);
        assertThat(service.getService(other, true, 0, 0).ejectedCount()).isEqualTo(1);
    }

    @Test
    void should_prefer_instance_scope_annotation_over_selectors() {
        registry.instances.put(SERVICE, List.of(instance("i1", Map.of("zone", "dc-2", "group", "canary"))));
        service.eject(EjectionTarget.selector(EjectionScope.GROUP, "canary"), "group down", OPERATOR);
        service.eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), "zone down", OPERATOR);
        service.eject(target("i1"), "instance down", OPERATOR);

        // 命中标注取最深一级（INSTANCE > SERVER > ZONE > GROUP）
        InstanceView view = service.getService(SERVICE, true, 0, 0).instances().get(0);
        assertThat(view.ejected()).isTrue();
        assertThat(view.reason()).isEqualTo("instance down");
    }

    // ---- 叠加与对消（操作记录即状态）----

    @Test
    void should_stack_ejections_and_require_matching_recovers() {
        service.eject(target("i1"), "first", OPERATOR);
        EjectionView second = service.eject(target("i1"), "second", OPERATOR);

        // 叠加：两条记录各留痕，仍为 active
        assertThat(second.reason()).isEqualTo("second");
        assertThat(second.sequence()).isGreaterThan(1);
        assertThat(service.listEjections(false, null, 10)).hasSize(2);
        assertThat(service.listEjections(true, null, 10)).hasSize(1);

        // 一次 recover 对消一条：仍 active
        service.recover(target("i1"), OPERATOR);
        assertThat(service.listEjections(true, null, 10)).hasSize(1);
        assertThat(service.listEjections(true, null, 10).get(0).reason()).isEqualTo("second");

        // 第二次 recover 完全恢复
        service.recover(target("i1"), OPERATOR);
        assertThat(service.listEjections(true, null, 10)).isEmpty();
        assertThat(service.listEjections(false, null, 10)).hasSize(4); // 2 EJECT + 2 RECOVER
    }

    @Test
    void should_be_idempotent_when_recovering_without_active_record() {
        service.recover(target("i1"), OPERATOR);
        service.recover(target("i1"), OPERATOR);

        assertThat(service.listEjections(false, null, 10)).isEmpty(); // 零追加
    }

    @Test
    void should_restore_instance_when_recovered() {
        registry.instances.put(SERVICE, List.of(instance("i1")));
        service.eject(target("i1"), "r", OPERATOR);
        service.recover(target("i1"), OPERATOR);

        assertThat(service.getService(SERVICE, false, 0, 0).instances()).hasSize(1);
    }

    @Test
    void should_not_touch_registry_when_ejecting_or_recovering() {
        int callsBefore = registry.calls;

        service.eject(target("i1"), "r", OPERATOR);
        service.recover(target("i1"), OPERATOR);

        assertThat(registry.calls).isEqualTo(callsBefore);
    }

    // ---- 规则流增量（仅生效态翻转）----

    @Test
    void should_notify_dispatcher_only_on_state_flip() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        EjectionView view = service.eject(target("i1"), "maintenance", OPERATOR);
        service.eject(target("i1"), "stacked", OPERATOR); // 叠加：不推
        service.recover(target("i1"), OPERATOR); // 仍 active：不推
        service.recover(target("i1"), OPERATOR); // 翻转入 inactive：推

        assertThat(subscriber.events)
                .containsExactly(
                        "snapshot:0:v0", "ejected:" + view.instanceId(), "recovered:" + view.instanceId() + ":v4");
    }

    @Test
    void should_not_fail_operation_when_publish_fails() {
        registry.instances.put(SERVICE, List.of(instance("i1")));
        dispatcher.subscribe(new ThrowingSubscriber());

        assertThat(service.eject(target("i1"), "r", OPERATOR)).isNotNull();
        assertThat(service.getService(SERVICE, true, 0, 0).ejectedCount()).isEqualTo(1);
        service.recover(target("i1"), OPERATOR);
        assertThat(service.getService(SERVICE, true, 0, 0).ejectedCount()).isZero();
    }

    @Test
    void should_send_active_rules_in_snapshot() {
        service.eject(target("i1"), "r1", OPERATOR);
        service.eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), "zone down", OPERATOR);
        service.recover(target("i1"), OPERATOR);

        RecordingSubscriber subscriber = new RecordingSubscriber();
        dispatcher.subscribe(subscriber);

        // 快照只含有效集（selector 级）
        assertThat(subscriber.events).containsExactly("snapshot:1:v3");
    }

    // ---- 审计 ----

    @Test
    void should_record_operator_on_ejection_and_audit() {
        service.eject(EjectionTarget.selector(EjectionScope.SERVER, "10.1.1.1"), "host down", OPERATOR);
        service.recover(EjectionTarget.selector(EjectionScope.SERVER, "10.1.1.1"), OPERATOR);

        List<EjectionView> records = service.listEjections(false, EjectionScope.SERVER, 10);
        assertThat(records)
                .extracting(EjectionView::operator, EjectionView::action)
                .containsExactly( // 时间倒序（最新在前）
                        org.assertj.core.groups.Tuple.tuple(
                                OPERATOR, org.mydotey.ai.artemis.management.domain.EjectionAction.RECOVER),
                        org.assertj.core.groups.Tuple.tuple(
                                OPERATOR, org.mydotey.ai.artemis.management.domain.EjectionAction.EJECT));

        List<AuditLog> logs = service.auditLogs(null, 10);
        assertThat(logs)
                .extracting(AuditLog::operation, AuditLog::target, AuditLog::result)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RECOVER", "server:10.1.1.1", "SUCCESS"),
                        org.assertj.core.groups.Tuple.tuple("EJECT", "server:10.1.1.1", "SUCCESS"));
    }

    @Test
    void should_query_audit_by_operation_with_limit() {
        service.eject(target("i1"), "r1", OPERATOR);
        auditLogs.append(new AuditLog("CHANGE_PASSWORD", "account:admin", "admin", "SUCCESS", null, NOW, 0));

        assertThat(service.auditLogs("EJECT", 10)).hasSize(1);
        assertThat(service.auditLogs("CHANGE_PASSWORD", 10))
                .singleElement()
                .extracting(AuditLog::operation)
                .isEqualTo("CHANGE_PASSWORD");
        assertThat(service.auditLogs(null, 1)).hasSize(1); // limit 生效（倒序首条）
    }

    @Test
    void should_keep_operation_when_audit_append_fails() {
        AuditLogStore failing = new AuditLogStore() {
            @Override
            public AuditLog append(AuditLog log) {
                throw new IllegalStateException("audit store down");
            }

            @Override
            public List<AuditLog> find(String operation, int limit) {
                return List.of();
            }
        };
        ManagementServiceImpl serviceWithFailingAudit = new ManagementServiceImpl(
                registry,
                ejections,
                failing,
                dispatcher,
                acks,
                logicalInstances,
                logicalDispatcher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(serviceWithFailingAudit.eject(target("i1"), "r", OPERATOR)).isNotNull();
        assertThat(service.listEjections(true, null, 10)).hasSize(1); // 摘除不受影响
    }

    // ---- 逻辑实例（v0.4 phase-4）----

    @Test
    void should_normalize_status_and_weight_when_upsert_logical_instance() {
        LogicalInstance stored = service.upsertLogicalInstance(
                new LogicalInstance(SERVICE, new Instance("i1", "10.0.0.1", 8080, null, 0, Map.of())), OPERATOR);
        LogicalInstance down = service.upsertLogicalInstance(
                new LogicalInstance(SERVICE, new Instance("i2", "10.0.0.2", 8080, InstanceStatus.DOWN, 200, Map.of())),
                OPERATOR);

        assertThat(stored.instance().status()).isEqualTo(InstanceStatus.UP); // UNSPECIFIED 语义等同 UP
        assertThat(stored.instance().weight()).isEqualTo(100); // ≤ 0 归一
        assertThat(down.instance().status()).isEqualTo(InstanceStatus.DOWN);
        assertThat(down.instance().weight()).isEqualTo(200);
        assertThat(logicalInstances.find(SERVICE, "i1")).isEqualTo(stored);
    }

    @Test
    void should_noop_and_skip_audit_and_push_when_upsert_identical_content() {
        LogicalInstance stored = service.upsertLogicalInstance(logical("i1"), OPERATOR);
        LogicalRecordingSubscriber subscriber = new LogicalRecordingSubscriber();
        logicalDispatcher.subscribe(subscriber); // 登记快照含 i1
        subscriber.events.clear();

        LogicalInstance result = service.upsertLogicalInstance(logical("i1"), OPERATOR);

        assertThat(result).isEqualTo(stored);
        assertThat(subscriber.events).isEmpty(); // 无增量推送
        assertThat(service.auditLogs("LOGICAL_INSTANCE_UPSERT", 10)).hasSize(1); // 仅首次审计
    }

    @Test
    void should_noop_when_delete_missing_logical_instance() {
        service.deleteLogicalInstance(SERVICE, "missing", OPERATOR);

        assertThat(service.auditLogs("LOGICAL_INSTANCE_DELETE", 10)).isEmpty();
        assertThat(service.listLogicalInstances(null, null, 10)).isEmpty();
    }

    @Test
    void should_append_audit_summary_on_logical_instance_change() {
        service.upsertLogicalInstance(logical("i1"), OPERATOR);
        service.deleteLogicalInstance(SERVICE, "i1", OPERATOR);

        assertThat(service.auditLogs(null, 10))
                .extracting(AuditLog::operation, AuditLog::target)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LOGICAL_INSTANCE_DELETE", "ns/svc/i1"),
                        org.assertj.core.groups.Tuple.tuple("LOGICAL_INSTANCE_UPSERT", "ns/svc/i1"));
    }

    @Test
    void should_push_increment_only_when_content_changes() {
        LogicalRecordingSubscriber subscriber = new LogicalRecordingSubscriber();
        logicalDispatcher.subscribe(subscriber);
        assertThat(subscriber.snapshot).isEmpty(); // 空快照（登记即推）

        service.upsertLogicalInstance(logical("i1"), OPERATOR);
        service.upsertLogicalInstance(logical("i1"), OPERATOR); // identical no-op
        service.deleteLogicalInstance(SERVICE, "i1", OPERATOR);
        service.deleteLogicalInstance(SERVICE, "i1", OPERATOR); // 缺失 no-op

        assertThat(subscriber.events).containsExactly("upserted:i1", "deleted:i1");
    }

    @Test
    void should_filter_and_limit_when_list_logical_instances() {
        service.upsertLogicalInstance(logical("i1"), OPERATOR);
        service.upsertLogicalInstance(
                new LogicalInstance(
                        new ServiceKey("other", "svc"), logical("i9").instance()),
                OPERATOR);

        assertThat(service.listLogicalInstances("ns", null, 10))
                .singleElement()
                .extracting(instance -> instance.instance().instanceId())
                .isEqualTo("i1");
        assertThat(service.listLogicalInstances(null, null, 1)).hasSize(1); // limit 生效
    }

    // ---- 透传 ----

    @Test
    void should_passthrough_lease_status_when_requested() {
        LeaseStatus status = new LeaseStatus(
                new SelfProtectionStats(5, 3, false), List.of(new LeaseDetail(SERVICE, "i1", 100, 900)), false);
        registry.leaseStatus = status;

        assertThat(service.leaseStatus("ns", "svc")).isSameAs(status);
    }

    @Test
    void should_passthrough_self_protection_status_when_requested() {
        SelfProtectionStatus status = new SelfProtectionStatus(
                1,
                1,
                new SelfProtectionStats(10, 100, false),
                List.of(new ServiceProtectionStatus(SERVICE, 0, 100, false, true, 1_000)),
                false);
        registry.selfProtectionStatus = status;

        assertThat(service.selfProtectionStatus("ns", "svc", true)).isSameAs(status);
    }

    @Test
    void should_passthrough_config_status_when_requested() {
        List<ConfigProperty> properties = List.of(new ConfigProperty("k", "v", "src", true, "d", "doc"));
        registry.properties = properties;

        assertThat(service.configStatus()).isSameAs(properties);
    }

    // ---- 设施 ----

    @Test
    void should_compose_rule_status_when_queried() {
        service.eject(target("i1"), "r", OPERATOR); // 翻转 → 版本 1
        acks.register("sub-1");
        acks.record("sub-1", service.ruleStatus().currentVersion());

        var status = service.ruleStatus();
        assertThat(status.currentVersion()).isEqualTo(1L);
        assertThat(status.appliedLatestCount()).isEqualTo(1);
        assertThat(status.subscribers()).singleElement().satisfies(watermark -> {
            assertThat(watermark.subscriberId()).isEqualTo("sub-1");
            assertThat(watermark.atLatest()).isTrue();
        });
    }

    private static EjectionTarget target(String instanceId) {
        return EjectionTarget.instance(SERVICE, instanceId);
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static Instance instance(String instanceId, Map<String, String> metadata) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, metadata);
    }

    /** 已归一形态（与 normalize 输出等值，供 identical no-op 判定）。 */
    private static LogicalInstance logical(String instanceId) {
        return new LogicalInstance(SERVICE, instance(instanceId));
    }

    private static final class LogicalRecordingSubscriber
            implements org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriber {

        final List<LogicalInstance> snapshot = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void onSnapshot(List<LogicalInstance> instances) {
            snapshot.addAll(instances);
        }

        @Override
        public void onUpserted(LogicalInstance instance) {
            events.add("upserted:" + instance.instance().instanceId());
        }

        @Override
        public void onDeleted(ServiceKey service, String instanceId) {
            events.add("deleted:" + instanceId);
        }

        @Override
        public void close() {}
    }

    private static final class RecordingSubscriber
            implements org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber {

        final List<String> events = new ArrayList<>();

        @Override
        public void onSnapshot(List<EjectionRecord> records, long version) {
            events.add("snapshot:" + records.size() + ":v" + version);
        }

        @Override
        public void onEjected(EjectionRecord record) {
            events.add("ejected:" + record.target().instanceId());
        }

        @Override
        public void onRecovered(EjectionTarget target, long version) {
            events.add("recovered:" + target.instanceId() + ":v" + version);
        }

        @Override
        public void close() {}
    }

    private static final class ThrowingSubscriber
            implements org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber {

        @Override
        public void onSnapshot(List<EjectionRecord> records, long version) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void onEjected(EjectionRecord record) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void onRecovered(EjectionTarget target, long version) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void close() {}
    }

    private static final class FakeRegistry implements RegistryQueryPort {

        private final List<ServiceSummary> services = new ArrayList<>();
        private final Map<ServiceKey, List<Instance>> instances = new HashMap<>();
        private LeaseStatus leaseStatus = new LeaseStatus(new SelfProtectionStats(0, 0, false), List.of(), false);
        private SelfProtectionStatus selfProtectionStatus =
                new SelfProtectionStatus(0, 0, new SelfProtectionStats(0, 0, false), List.of(), false);
        private List<ConfigProperty> properties = List.of();
        private int calls;

        @Override
        public List<ServiceSummary> listServices(String namespace) {
            calls++;
            return services;
        }

        @Override
        public List<Instance> getInstances(ServiceKey key) {
            calls++;
            return instances.getOrDefault(key, List.of());
        }

        @Override
        public LeaseStatus leaseStatus(String namespace, String service) {
            calls++;
            return leaseStatus;
        }

        @Override
        public SelfProtectionStatus selfProtectionStatus(String namespace, String service, boolean includeNormal) {
            calls++;
            return selfProtectionStatus;
        }

        @Override
        public List<ConfigProperty> configStatus() {
            calls++;
            return properties;
        }
    }
}
