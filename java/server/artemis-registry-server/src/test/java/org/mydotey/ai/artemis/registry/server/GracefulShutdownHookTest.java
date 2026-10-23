package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/** 停机序列编排：markDown 先于终刷、幂等、终刷异常不阻断关闭（drainMs 固定 0，不实等）。 */
class GracefulShutdownHookTest {

    private static final ClusterMember SELF =
            new ClusterMember("node-a", new ClusterEndpoint("10.0.0.1", 9093), new ClusterEndpoint("10.0.0.1", 9090));

    private final AnnotationConfigApplicationContext source = new AnnotationConfigApplicationContext();

    @Test
    void should_runShutdownSequence_when_contextClosed() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        RegistryService service = mock(RegistryService.class);

        hook(gate, service).onApplicationEvent(closedEvent());

        assertThat(gate.isDown()).isTrue();
        assertThat(gate.isReady()).isFalse(); // readiness 联动统一拒新流量
        verify(service).writeSnapshot();
    }

    @Test
    void should_executeOnlyOnce_when_eventRepeated() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        RegistryService service = mock(RegistryService.class);
        GracefulShutdownHook hook = hook(gate, service);

        hook.onApplicationEvent(closedEvent());
        hook.onApplicationEvent(closedEvent());

        verify(service).writeSnapshot(); // 恰一次
        verifyNoMoreInteractions(service);
    }

    @Test
    void should_notThrow_when_finalSnapshotFails() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        RegistryService service = mock(RegistryService.class);
        doThrow(new IllegalStateException("disk full")).when(service).writeSnapshot();

        assertThatCode(() -> hook(gate, service).onApplicationEvent(closedEvent()))
                .doesNotThrowAnyException();
        assertThat(gate.isDown()).isTrue(); // 标记已生效，快照失败回退 peer 同步 / TTL
    }

    @Test
    void should_skipFinalSnapshot_when_neverReady() {
        ReadinessGate gate = new ReadinessGate(); // 启动数据源未取得：注册表为空/残缺
        RegistryService service = mock(RegistryService.class);

        hook(gate, service).onApplicationEvent(closedEvent());

        assertThat(gate.isDown()).isTrue();
        verifyNoInteractions(service); // 空表不得覆盖可回退的旧快照
    }

    private GracefulShutdownHook hook(ReadinessGate gate, RegistryService service) {
        return new GracefulShutdownHook(
                gate,
                service,
                members(),
                TestProperties.of(Map.of(GracefulShutdownHook.KEY_DRAIN_MS, "0"))
                        .properties());
    }

    private ContextClosedEvent closedEvent() {
        return new ContextClosedEvent(source);
    }

    private static ClusterMembers members() {
        return new ClusterMembers() {
            @Override
            public List<ClusterMember> members() {
                return List.of(SELF);
            }

            @Override
            public Optional<ClusterMember> self() {
                return Optional.of(SELF);
            }
        };
    }
}
