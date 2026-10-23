package org.mydotey.ai.artemis.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;

/** 聚合内核语义：四操作事件派生、心跳对账（无集合 diff）、租约续约与过期清理、快照共享。 */
class InstanceRegistryTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    private MutableClock clock;
    private List<InstanceChange> events;
    private TestProperties properties;
    private InstanceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        events = new ArrayList<>();
        properties = TestProperties.of(Map.of(InstanceRegistry.KEY_LEASE_TTL_MS, "30000"));
        registry = new InstanceRegistry(properties.properties(), clock, events::add);
    }

    // ---- register ----

    @Test
    void should_emit_new_and_create_lease_when_register_absent() {
        registry.register(KEY, List.of(instance("i1")));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(ChangeType.NEW);
        assertThat(events.get(0).instance().instanceId()).isEqualTo("i1");
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i1"));
    }

    @Test
    void should_emit_new_when_register_existing_instance() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        registry.register(KEY, List.of(instance("i1")));

        // register 是显式声明注册语义：无条件覆盖且事件类型不依赖内容比较
        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.NEW);
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances()).hasSize(1));
    }

    // ---- deregister ----

    @Test
    void should_emit_delete_and_remove_when_deregister_present() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        List<String> removed = registry.deregister(KEY, List.of("i1", "absent"));

        assertThat(removed).containsExactly("i1");
        assertThat(events).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(ChangeType.DELETE);
            assertThat(c.instance().instanceId()).isEqualTo("i1");
        });
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_return_empty_when_deregister_absent() {
        List<String> removed = registry.deregister(KEY, List.of("i1"));

        assertThat(removed).isEmpty();
        assertThat(events).isEmpty();
    }

    // ---- update ----

    @Test
    void should_emit_update_when_update_hit() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        registry.update(KEY, List.of(instance("i1", 200)));

        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.UPDATE);
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(
                        s -> assertThat(s.instances().get(0).weight()).isEqualTo(200));
    }

    @Test
    void should_emit_new_and_create_lease_when_update_absent() {
        registry.update(KEY, List.of(instance("i1")));

        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.NEW);
        assertThat(registry.snapshot(KEY)).isPresent();
    }

    // ---- heartbeat ----

    @Test
    void should_emit_renew_when_heartbeat_content_unchanged() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        registry.heartbeatUpsert(KEY, List.of(instance("i1")));

        // 纯续约发 RENEW（集群模式扇出维持 peer 副本租约；单节点 no-op listener 吞掉）
        assertThat(events).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(ChangeType.RENEW);
            assertThat(c.instance().instanceId()).isEqualTo("i1");
        });
    }

    @Test
    void should_emit_new_when_heartbeat_instance_absent() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        registry.heartbeatUpsert(KEY, List.of(instance("i1"), instance("i2")));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(InstanceChange::type).containsExactly(ChangeType.RENEW, ChangeType.NEW);
        assertThat(events.get(1).instance().instanceId()).isEqualTo("i2");
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances()).hasSize(2));
    }

    @Test
    void should_emit_update_and_overwrite_when_heartbeat_content_changed() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();

        registry.heartbeatUpsert(KEY, List.of(instance("i1", 200)));

        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.UPDATE);
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(
                        s -> assertThat(s.instances().get(0).weight()).isEqualTo(200));
    }

    @Test
    void should_not_remove_absent_instances_when_heartbeat_partial() {
        registry.register(KEY, List.of(instance("i1"), instance("i2")));
        events.clear();

        // 心跳只报部分实例：不做集合 diff，未上报实例仍在（等 TTL）；在场的纯续约发 RENEW
        registry.heartbeatUpsert(KEY, List.of(instance("i1")));

        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances()).hasSize(2));
        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.RENEW);
    }

    // ---- 租约与过期清理 ----

    @Test
    void should_defer_expiry_when_lease_renewed() {
        registry.register(KEY, List.of(instance("i1")));
        clock.advance(Duration.ofSeconds(29));
        registry.heartbeatUpsert(KEY, List.of(instance("i1"))); // 续约点 t=29s
        clock.advance(Duration.ofSeconds(20)); // t=49s，距续约 20s < TTL 30s

        assertThat(registry.evictExpired()).isZero();
        assertThat(registry.snapshot(KEY)).isPresent();

        clock.advance(Duration.ofSeconds(11)); // t=60s，距续约 31s >= TTL
        assertThat(registry.evictExpired()).isEqualTo(1);
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_emit_evicted_when_lease_expired() {
        registry.register(KEY, List.of(instance("i1")));
        events.clear();
        clock.advance(Duration.ofSeconds(30));

        int evicted = registry.evictExpired();

        // 统一事件源：清理派发 EVICTED（复制扇出在发送侧过滤——删除不跨节点传播）
        assertThat(evicted).isEqualTo(1);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(ChangeType.EVICTED);
            assertThat(event.instance().instanceId()).isEqualTo("i1");
        });
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_emit_eviction_after_later_write_events() {
        registry.register(KEY, List.of(instance("i1"))); // t=0，i1 到期点 t=30
        clock.advance(Duration.ofSeconds(20));
        registry.register(KEY, List.of(instance("i2"))); // t=20，i2 到期点 t=50
        clock.advance(Duration.ofSeconds(11)); // t=31：仅 i1 过期

        registry.evictExpired();

        assertThat(events)
                .extracting(InstanceChange::instance, InstanceChange::type)
                .containsExactly(
                        tuple(instance("i1"), ChangeType.NEW),
                        tuple(instance("i2"), ChangeType.NEW),
                        tuple(instance("i1"), ChangeType.EVICTED));
    }

    @Test
    void should_not_evict_when_within_ttl() {
        registry.register(KEY, List.of(instance("i1")));
        clock.advance(Duration.ofMillis(29_999)); // now - renewal < ttl

        assertThat(registry.evictExpired()).isZero();
        assertThat(registry.snapshot(KEY)).isPresent();
    }

    @Test
    void should_use_latest_ttl_when_config_changes() {
        registry.register(KEY, List.of(instance("i1")));
        properties.set(InstanceRegistry.KEY_LEASE_TTL_MS, "5000"); // 动态缩短
        clock.advance(Duration.ofSeconds(5));

        assertThat(registry.evictExpired()).isEqualTo(1);
    }

    @Test
    void should_evict_when_ttl_source_changes_and_clock_advances() {
        registry.register(KEY, List.of(instance("i1")));
        clock.advance(Duration.ofSeconds(29));
        assertThat(registry.evictExpired()).isZero();

        properties.set(InstanceRegistry.KEY_LEASE_TTL_MS, "1000");
        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.evictExpired()).isEqualTo(1);
    }

    @Test
    void should_remove_service_when_last_instance_evicted() {
        registry.register(KEY, List.of(instance("i1")));
        clock.advance(Duration.ofSeconds(30));
        registry.evictExpired();
        registry.register(KEY, List.of(instance("i2"))); // 空 service 回收后可重新注册

        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i2"));
    }

    // ---- 快照 ----

    @Test
    void should_return_shared_snapshot_when_get_instances() {
        registry.register(KEY, List.of(instance("i1")));

        Optional<ServiceInstances> first = registry.snapshot(KEY);
        Optional<ServiceInstances> second = registry.snapshot(KEY);

        assertThat(first).isPresent();
        assertThat(second).containsSame(first.orElseThrow());
        assertThat(registry.snapshot(new ServiceKey("default", "unknown"))).isEmpty();
    }

    @Test
    void should_not_rebuild_snapshot_when_pure_renewal() {
        registry.register(KEY, List.of(instance("i1")));
        ServiceInstances before = registry.snapshot(KEY).orElseThrow();

        clock.advance(Duration.ofSeconds(1));
        registry.heartbeatUpsert(KEY, List.of(instance("i1")));

        assertThat(registry.snapshot(KEY)).containsSame(before);
    }

    // ---- 复制写（phase-2 副本租约语义，原产品继承）----

    @Test
    void should_write_replica_without_change_event_when_replicate_applied() {
        int applied = registry.applyReplicated(List.of(replicated(ChangeType.NEW, instance("i1"))));
        registry.applyReplicated(List.of(replicated(ChangeType.UPDATE, instance("i1", 200))));

        // 零事件（防回环，同 restore 不变量）；副本为真实租约且内容覆盖
        assertThat(applied).isEqualTo(1);
        assertThat(events).isEmpty();
        assertThat(registry.snapshot(KEY)).hasValueSatisfying(s -> {
            assertThat(s.instances()).hasSize(1);
            assertThat(s.instances().get(0).weight()).isEqualTo(200);
        });
    }

    @Test
    void should_renew_replica_when_replicated_renew_hits() {
        registry.applyReplicated(List.of(replicated(ChangeType.NEW, instance("i1"))));
        clock.advance(Duration.ofSeconds(29)); // 副本租约 29s 前建立
        registry.applyReplicated(List.of(replicated(ChangeType.RENEW, instance("i1"))));
        clock.advance(Duration.ofSeconds(20)); // 距 RENEW 20s < TTL 30s

        assertThat(registry.evictExpired()).isZero();
        assertThat(registry.snapshot(KEY)).isPresent();
    }

    @Test
    void should_upsert_replica_when_replicated_renew_absent() {
        // 副本已被本地清理（分区恢复）：RENEW 到达 → 补注册重建，零事件
        registry.applyReplicated(List.of(replicated(ChangeType.RENEW, instance("i1"))));

        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i1"));
        assertThat(events).isEmpty();
    }

    @Test
    void should_apply_replicated_delete_idempotently() {
        registry.applyReplicated(List.of(replicated(ChangeType.DELETE, instance("i1")))); // 缺席 no-op

        assertThat(registry.snapshot(KEY)).isEmpty();

        registry.applyReplicated(List.of(replicated(ChangeType.NEW, instance("i1"))));
        registry.applyReplicated(List.of(replicated(ChangeType.DELETE, instance("i1")))); // 命中删除

        assertThat(registry.snapshot(KEY)).isEmpty();
        assertThat(events).isEmpty();
    }

    // ---- 全量快照读与回放写（phase-3）----

    @Test
    void should_expose_full_registry_for_snapshot() {
        ServiceKey other = new ServiceKey("default", "pay-service");
        registry.register(KEY, List.of(instance("i1")));
        registry.register(other, List.of(instance("p1"), instance("p2")));

        List<ServiceInstances> all = registry.snapshots();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(ServiceInstances::key).containsExactlyInAnyOrder(KEY, other);
        assertThat(all.stream().mapToInt(s -> s.instances().size()).sum()).isEqualTo(3);
    }

    @Test
    void should_restore_without_events() {
        events.clear();
        List<ServiceInstances> snapshot = List.of(
                new ServiceInstances(KEY, List.of(instance("i1"), instance("i2"))),
                new ServiceInstances(new ServiceKey("default", "pay-service"), List.of(instance("p1"))));

        registry.restore(snapshot);

        // 回放零事件（D4 不变量）：listener 零回调，但快照重建可见
        assertThat(events).isEmpty();
        assertThat(registry.snapshots()).hasSize(2);
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactlyInAnyOrder("i1", "i2"));
    }

    @Test
    void should_reset_lease_from_restore_time() {
        // 回放数据「携带」的陈旧性在回放时点不结算：续约时刻 = 回放时刻，TTL 从回放起算
        clock.advance(Duration.ofMinutes(10)); // 回放前时钟已远超 TTL
        registry.restore(List.of(new ServiceInstances(KEY, List.of(instance("i1")))));

        clock.advance(Duration.ofSeconds(29)); // 距回放 29s < TTL 30s：回放前已「过期」的实例不摘
        assertThat(registry.evictExpired()).isZero();
        assertThat(registry.snapshot(KEY)).isPresent();

        clock.advance(Duration.ofSeconds(2)); // 距回放 31s >= TTL
        assertThat(registry.evictExpired()).isEqualTo(1);
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_replace_all_and_remove_absent_services_without_events() {
        ServiceKey stale = new ServiceKey("default", "stale-service");
        registry.register(KEY, List.of(instance("old")));
        registry.register(stale, List.of(instance("s1")));
        events.clear();

        registry.replaceAll(List.of(new ServiceInstances(KEY, List.of(instance("i1"), instance("i2")))));

        assertThat(events).isEmpty(); // 启动期整体替换零事件（D4）
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactlyInAnyOrder("i1", "i2"));
        assertThat(registry.snapshot(stale)).isEmpty(); // 快照中缺席的 service 被移除
        assertThat(registry.snapshots()).hasSize(1);
    }

    @Test
    void should_clear_registry_when_replace_all_with_empty_snapshot() {
        registry.register(KEY, List.of(instance("i1")));

        registry.replaceAll(List.of());

        assertThat(registry.snapshots()).isEmpty();
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_reset_lease_from_replace_all_time() {
        clock.advance(Duration.ofSeconds(29));
        registry.replaceAll(List.of(new ServiceInstances(KEY, List.of(instance("i1")))));
        clock.advance(Duration.ofSeconds(29)); // 距替换 29s < TTL

        assertThat(registry.evictExpired()).isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(registry.evictExpired()).isEqualTo(1);
    }

    // ---- 租约明细与 TTL 配置（内部只读查询）----

    @Test
    void should_report_lease_details_when_registered() {
        registry.register(KEY, List.of(instance("i1"), instance("i2")));

        clock.advance(Duration.ofSeconds(10));

        assertThat(registry.leaseDetails())
                .extracting(
                        LeaseDetail::service, LeaseDetail::instanceId, LeaseDetail::ageMs, LeaseDetail::remainingTtlMs)
                .containsExactlyInAnyOrder(tuple(KEY, "i1", 10_000L, 20_000L), tuple(KEY, "i2", 10_000L, 20_000L));
    }

    @Test
    void should_apply_dynamic_ttl_when_configured() {
        TestProperties configuredProperties = TestProperties.of(Map.of(InstanceRegistry.KEY_LEASE_TTL_MS, "5000"));
        InstanceRegistry configured = new InstanceRegistry(configuredProperties.properties(), clock, events::add);

        configured.register(KEY, List.of(instance("i1")));
        clock.advance(Duration.ofMillis(4_999));
        assertThat(configured.evictExpired()).isZero();
        assertThat(configured.leaseDetails())
                .singleElement()
                .satisfies(detail -> assertThat(detail.remainingTtlMs()).isEqualTo(1L));

        configuredProperties.set(InstanceRegistry.KEY_LEASE_TTL_MS, "1000");
        clock.advance(Duration.ofMillis(2));
        assertThat(configured.evictExpired()).isEqualTo(1);
    }

    // ---- 设施 ----

    /** 入站复制事件构造（changeTimeMs 固定 0：源节点单调钟不可跨进程，D14）。 */
    private static InstanceChange replicated(ChangeType type, Instance instance) {
        return new InstanceChange(KEY, instance, type, 0L);
    }

    private static Instance instance(String instanceId) {
        return instance(instanceId, 100);
    }

    private static Instance instance(String instanceId, int weight) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, weight, Map.of("zone", "a"));
    }

    // ---- 事件定序（D13）----

    @Test
    void should_keep_last_event_consistent_with_final_state_when_concurrent_ops_on_same_key()
            throws InterruptedException {
        List<InstanceChange> concurrentEvents = new CopyOnWriteArrayList<>();
        InstanceRegistry concurrentRegistry =
                new InstanceRegistry(properties.properties(), clock, concurrentEvents::add);
        int rounds = 200;
        // 内容交替：register 每轮必有 NEW 事件（无条件覆盖）；deregister 命中才有 DELETE
        Thread registrar = new Thread(
                () -> {
                    for (int i = 0; i < rounds; i++) {
                        concurrentRegistry.register(KEY, List.of(instance("i1", 100 + i % 2)));
                    }
                },
                "ordering-registrar");
        Thread deregistrar = new Thread(
                () -> {
                    for (int i = 0; i < rounds; i++) {
                        concurrentRegistry.deregister(KEY, List.of("i1"));
                    }
                },
                "ordering-deregistrar");
        registrar.start();
        deregistrar.start();
        registrar.join();
        deregistrar.join();

        // 同 key 全同步（含锁内派发 + 回收后置）：最后一事件必与存储终态一致——
        // 终态在场 → 非 DELETE；终态缺席（holder 已回收）→ DELETE。无幻影终态事件。
        boolean present = concurrentRegistry.snapshot(KEY).isPresent();
        assertThat(concurrentEvents).isNotEmpty();
        assertThat(concurrentEvents.get(concurrentEvents.size() - 1).type() == ChangeType.DELETE)
                .isEqualTo(!present);
    }

    @Test
    void should_keep_storage_invariants_when_listener_throws() {
        List<InstanceChange> delivered = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        InstanceRegistry guarded = new InstanceRegistry(properties.properties(), clock, change -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("listener boom"); // 首个事件抛出
            }
            delivered.add(change);
        });

        // register 两实例：首事件异常被隔离（不逃逸），批内第二事件照常派发，写入与快照完整
        guarded.register(KEY, List.of(instance("i1"), instance("i2")));
        assertThat(guarded.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances()).hasSize(2));
        assertThat(delivered).hasSize(1);

        // 回收尾在派发后仍执行：listener 异常不阻断空 holder 回收（存储不变量不依赖 listener 行为）
        assertThat(guarded.deregister(KEY, List.of("i1", "i2"))).containsExactly("i1", "i2");
        assertThat(guarded.snapshot(KEY)).isEmpty();
        assertThat(delivered).hasSize(3); // 后续 DELETE 两条照常派发
    }

    @Test
    void should_expose_consistent_snapshot_in_listener_when_last_instance_removed() {
        InstanceRegistry[] box = new InstanceRegistry[1];
        List<Optional<ServiceInstances>> observed = new ArrayList<>();
        box[0] = new InstanceRegistry(properties.properties(), clock, change -> observed.add(box[0].snapshot(KEY)));

        box[0].register(KEY, List.of(instance("i1")));
        observed.clear();

        box[0].deregister(KEY, List.of("i1"));

        // DELETE 派发期间（锁内回调）读到的快照已不含被摘实例——事件与可读状态一致（D13 提交尾）
        Optional<ServiceInstances> last = observed.get(observed.size() - 1);
        assertThat(last.map(s -> s.instances().isEmpty()).orElse(true)).isTrue();
    }
}
