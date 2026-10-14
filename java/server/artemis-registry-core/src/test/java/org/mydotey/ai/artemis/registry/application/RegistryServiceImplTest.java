package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponse;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 应用服务：契约级校验/归一、批量部分失败语义、响应字段组装。 */
class RegistryServiceImplTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    private MutableClock clock;
    private List<InstanceChange> events;
    private RegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        events = new ArrayList<>();
        LeaseTtlSource ttl = () -> Duration.ofSeconds(30);
        ChangeListener listener = events::add;
        service = new RegistryServiceImpl(ttl, clock, clock, listener);
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
        RegistryServiceImpl split = new RegistryServiceImpl(() -> Duration.ofSeconds(30), wall, mono, events::add);

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
