package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponse;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.SelfProtectionConfig;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 应用服务：契约级校验/归一、批量部分失败语义、响应字段组装、保护编排与快照回放。 */
class RegistryServiceImplTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    /** 测试保护参数：低门槛（minCount 5）便于构造保护态；10s 窗口与生产默认一致。 */
    private static final SelfProtectionConfig PROTECTION_CONFIG =
            new SelfProtectionConfig(Duration.ofSeconds(10), 0.85, 5);

    private static final Duration MAX_AGE = Duration.ofHours(1);

    private MutableClock clock;
    private List<InstanceChange> events;
    private SelfProtection protection;
    private InMemorySnapshotStore store;
    private RegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        events = new ArrayList<>();
        LeaseTtlSource ttl = () -> Duration.ofSeconds(30);
        ChangeListener listener = events::add;
        protection = new SelfProtection(PROTECTION_CONFIG, clock);
        store = new InMemorySnapshotStore();
        service = new RegistryServiceImpl(ttl, clock, clock, listener, protection, store, MAX_AGE);
    }

    @Test
    void should_fail_group_and_skip_when_service_key_invalid() {
        RegisterRequest request = new RegisterRequest(List.of(
                new ServiceInstances(new ServiceKey("", "order-service"), List.of(instance("i1"))),
                new ServiceInstances(KEY, List.of(instance("i2")))));

        RegisterResponse response = service.register(request);

        // 非法分组整体拒绝不写入、代表性错误进 status（结构上无法表达实例级明细）；合法分组照常生效
        assertThat(response.status().ack()).isEqualTo(Ack.FAIL);
        assertThat(response.status().error().code()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
        assertThat(response.data().services()).isEmpty();
        assertThat(service.getInstances(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances()).hasSize(1));
    }

    @Test
    void should_report_instance_error_when_instance_id_empty() {
        RegisterRequest request =
                new RegisterRequest(List.of(new ServiceInstances(KEY, List.of(instance(""), instance("i2")))));

        RegisterResponse response = service.register(request);

        assertThat(response.status().ack()).isEqualTo(Ack.FAIL);
        assertThat(response.data().services()).singleElement().satisfies(group -> {
            assertThat(group.key()).isEqualTo(KEY);
            assertThat(group.failed()).singleElement().satisfies(error -> {
                assertThat(error.error().code()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
                assertThat(error.instance().instanceId()).isEmpty();
            });
        });
        // 其余实例照常生效
        assertThat(service.getInstances(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i2"));
    }

    @Test
    void should_normalize_weight_when_not_positive() {
        service.register(new RegisterRequest(
                List.of(new ServiceInstances(KEY, List.of(instance("i1"), instanceWithWeight("i2", 0))))));

        assertThat(service.getInstances(KEY)).hasValueSatisfying(s -> {
            assertThat(s.instances().get(0).weight()).isEqualTo(100);
            assertThat(s.instances().get(1).weight()).isEqualTo(100); // 存储与快照为归一值，不判失败
        });
    }

    @Test
    void should_ack_success_when_deregister_absent() {
        var response = service.deregister(
                new DeregisterRequest(List.of(new org.mydotey.ai.artemis.registry.domain.InstanceKey(KEY, "i1"))));

        assertThat(response.status().ack()).isEqualTo(Ack.SUCCESS);
        assertThat(response.status().error()).isNull();
        assertThat(response.data().instances()).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void should_echo_seq_and_server_time_when_heartbeat() {
        clock.advance(Duration.ofSeconds(42));

        HeartbeatResponse response = service.heartbeat(
                new HeartbeatRequest(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), 99L));

        assertThat(response.data().seq()).isEqualTo(99L);
        assertThat(response.data().serverTimeMs()).isEqualTo(clock.currentMillis());
        assertThat(response.status().ack()).isEqualTo(Ack.SUCCESS);
    }

    // ---- 保护编排（D1：判定与计数在应用层）----

    @Test
    void should_count_heartbeat_renewals_only() {
        service.heartbeat(new HeartbeatRequest(
                List.of(new ServiceInstances(KEY, List.of(instance("i1"), instance("i2")))), 1L));

        assertThat(protection.stats().windowRenewals()).isEqualTo(2);

        // 显式操作非续约信号，不计入窗口
        service.register(new RegisterRequest(List.of(new ServiceInstances(KEY, List.of(instance("i3"))))));
        service.heartbeat(new HeartbeatRequest(
                List.of(new ServiceInstances(KEY, List.of(instance("i1"), instance("i2")))), 2L));
        assertThat(protection.stats().windowRenewals()).isEqualTo(4);

        service.deregister(
                new DeregisterRequest(List.of(new org.mydotey.ai.artemis.registry.domain.InstanceKey(KEY, "i1"))));
        assertThat(protection.stats().windowRenewals()).isEqualTo(4);
    }

    @Test
    void should_skip_eviction_when_protected() {
        // 心跳 100 达峰（≥ minCount 5）→ 静默 11s 滑出窗口 → 续约量 0 < 100×0.85 → 保护态
        service.heartbeat(new HeartbeatRequest(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), 1L));
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.isProtected()).isTrue();

        clock.advance(Duration.ofSeconds(30)); // 距最后续约超 TTL
        assertThat(service.evictExpired()).isZero(); // 保护态整轮跳过
        assertThat(service.getInstances(KEY)).isPresent();

        // 退出保护（心跳恢复）后过期照常清理
        protection.recordRenewals(90);
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.evictExpired()).isEqualTo(1);
        assertThat(service.getInstances(KEY)).isEmpty();
    }

    @Test
    void should_allow_deregister_when_protected() {
        service.register(new RegisterRequest(List.of(new ServiceInstances(KEY, List.of(instance("i1"))))));
        protection.recordRenewals(100);
        clock.advance(Duration.ofSeconds(11));
        assertThat(protection.isProtected()).isTrue();
        events.clear();

        var response = service.deregister(
                new DeregisterRequest(List.of(new org.mydotey.ai.artemis.registry.domain.InstanceKey(KEY, "i1"))));

        // 显式摘除不受保护（架构 §4.1 边界）：照常移除并发 DELETE 事件
        assertThat(response.status().ack()).isEqualTo(Ack.SUCCESS);
        assertThat(service.getInstances(KEY)).isEmpty();
        assertThat(events).singleElement().extracting(InstanceChange::type).isEqualTo(ChangeType.DELETE);
    }

    // ---- 快照落盘与回放（D3/D6/D10）----

    @Test
    void should_treat_stale_snapshot_as_invalid() {
        clock.advance(Duration.ofHours(2)); // now = 2h，快照时间戳 = 0 → 超 maxAge 1h
        store.write(new RegistrySnapshotCodec()
                .encode(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), 0L));

        assertThat(service.restoreFromSnapshot()).isZero();
        assertThat(service.getInstances(KEY)).isEmpty(); // 空表启动，回放被跳过
    }

    @Test
    void should_accept_future_timestamp_snapshot_within_clock_skew_tolerance() {
        clock.advance(Duration.ofHours(2));
        long future = clock.currentMillis() + Duration.ofSeconds(30).toMillis(); // 墙钟回拨宽容（≤ 1min）
        store.write(new RegistrySnapshotCodec()
                .encode(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), future));

        assertThat(service.restoreFromSnapshot()).isEqualTo(1);
        assertThat(service.getInstances(KEY)).isPresent();
    }

    @Test
    void should_discard_far_future_timestamp_snapshot() {
        clock.advance(Duration.ofHours(2));
        long farFuture = clock.currentMillis() + Duration.ofMinutes(10).toMillis(); // 超出回拨宽容：损坏/异常时钟
        store.write(new RegistrySnapshotCodec()
                .encode(List.of(new ServiceInstances(KEY, List.of(instance("i1")))), farFuture));

        assertThat(service.restoreFromSnapshot()).isZero();
        assertThat(service.getInstances(KEY)).isEmpty();
    }

    @Test
    void should_drop_invalid_entries_when_restoring_snapshot() {
        // 可解析但语义损坏：空 instanceId、weight=0、空 key——回放须走与 register 相同的校验/归一门
        ServiceInstances corrupt =
                new ServiceInstances(KEY, List.of(instance(""), instanceWithWeight("i2", 0), instance("i3")));
        store.write(new RegistrySnapshotCodec()
                .encode(List.of(corrupt, new ServiceInstances(new ServiceKey("", ""), List.of(instance("i4")))), 0L));

        assertThat(service.restoreFromSnapshot()).isEqualTo(2); // i2（归一）+ i3；空 id 与空 key 丢弃
        assertThat(service.getInstances(KEY)).hasValueSatisfying(s -> {
            assertThat(s.instances()).extracting(Instance::instanceId).containsExactly("i2", "i3");
            assertThat(s.instances()).extracting(Instance::weight).containsExactly(100, 100);
        });
    }

    // ---- 设施 ----

    private static Instance instance(String instanceId) {
        return instanceWithWeight(instanceId, 100);
    }

    private static Instance instanceWithWeight(String instanceId, int weight) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, weight, Map.of("zone", "a"));
    }

    @Test
    void should_expire_by_monotonic_clock_when_wall_clock_steps_back() {
        MutableClock wall = new MutableClock();
        MutableClock mono = new MutableClock();
        wall.advance(Duration.ofMillis(1_700_000_000_000L)); // 两域取完全不同的值域，混用即断言失败
        RegistryServiceImpl split = new RegistryServiceImpl(
                () -> Duration.ofSeconds(30),
                wall,
                mono,
                events::add,
                new SelfProtection(PROTECTION_CONFIG, mono),
                new InMemorySnapshotStore(),
                MAX_AGE);

        split.register(new RegisterRequest(List.of(new ServiceInstances(KEY, List.of(instance("i1"))))));

        wall.advance(Duration.ofSeconds(-60)); // 壁挂后跳（NTP 场景）
        mono.advance(Duration.ofSeconds(31)); // 单调推进超 TTL

        // 过期只认单调钟：壁挂后跳不影响摘除（D14 回归守护——两钟同值时本用例无区分度）
        assertThat(split.evictExpired()).isEqualTo(1);
        assertThat(split.getInstances(KEY)).isEmpty();

        // serverTimeMs 走壁挂钟（跨进程时间戳）：等于 wall 当前值、异于 mono 值
        HeartbeatResponse heartbeat =
                split.heartbeat(new HeartbeatRequest(List.of(new ServiceInstances(KEY, List.of())), 7L));
        assertThat(heartbeat.data().serverTimeMs()).isEqualTo(wall.millis());
        assertThat(heartbeat.data().serverTimeMs()).isNotEqualTo(mono.monoMillis());
    }
}
