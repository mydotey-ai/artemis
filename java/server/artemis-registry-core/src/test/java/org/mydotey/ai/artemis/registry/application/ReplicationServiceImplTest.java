package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 入站复制用例：信任边界校验（整批拒绝）+ 零事件应用。 */
class ReplicationServiceImplTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");
    private static final ClusterMember SELF = new ClusterMember("node-a", new ClusterEndpoint("10.0.0.1", 9093));

    private final List<InstanceChange> events = new ArrayList<>();
    private InstanceRegistry registry;
    private ReplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        registry = new InstanceRegistry(TestProperties.empty(), new MutableClock(), events::add);
        service = new ReplicationServiceImpl(
                members(), new ReadinessGate(), registry, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    @Test
    void should_reject_batch_when_source_node_id_empty() {
        ResponseStatus status = service.replicate(new ReplicationBatch("", List.of(change(ChangeType.NEW, "i1"))));

        assertThat(status.ack()).isEqualTo(Ack.FAIL);
        assertThat(status.error().code()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
        assertThat(registry.snapshot(KEY)).isEmpty();
    }

    @Test
    void should_reject_batch_when_instance_id_blank() {
        ResponseStatus status = service.replicate(new ReplicationBatch("node-b", List.of(change(ChangeType.NEW, " "))));

        assertThat(status.ack()).isEqualTo(Ack.FAIL);
        assertThat(status.error().code()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
    }

    @Test
    void should_reject_batch_when_service_key_blank() {
        // 与外部 register/update/heartbeat 入口对齐：blank namespace/service 不进注册表（防空键残留）
        InstanceChange blankKey = new InstanceChange(
                new ServiceKey("", "order-service"),
                new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, java.util.Map.of()),
                ChangeType.NEW,
                0L);
        ResponseStatus status = service.replicate(new ReplicationBatch("node-b", List.of(blankKey)));

        assertThat(status.ack()).isEqualTo(Ack.FAIL);
        assertThat(status.error().code()).isEqualTo(ErrorCodes.INVALID_ARGUMENT);
        assertThat(registry.snapshot(new ServiceKey("", "order-service"))).isEmpty();
    }

    @Test
    void should_apply_without_event_when_batch_valid() {
        ResponseStatus status = service.replicate(
                new ReplicationBatch("node-b", List.of(change(ChangeType.NEW, "i1"), change(ChangeType.RENEW, "i2"))));

        assertThat(status.ack()).isEqualTo(Ack.SUCCESS);
        assertThat(events).isEmpty();
        assertThat(registry.snapshot(KEY))
                .hasValueSatisfying(s -> assertThat(s.instances())
                        .extracting(Instance::instanceId)
                        .containsExactly("i1", "i2"));
    }

    private static InstanceChange change(ChangeType type, String instanceId) {
        return new InstanceChange(
                KEY, new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, java.util.Map.of()), type, 0L);
    }

    private static ClusterMembers members() {
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of(SELF);
            }

            @Override
            public java.util.Optional<ClusterMember> self() {
                return java.util.Optional.of(SELF);
            }
        };
    }
}
