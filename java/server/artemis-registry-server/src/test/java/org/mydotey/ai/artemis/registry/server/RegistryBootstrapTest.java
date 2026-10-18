package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.application.RegistryServiceImpl;
import org.mydotey.ai.artemis.registry.application.RegistrySnapshotCodec;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.infrastructure.FileRegistrySnapshotStore;

/**
 * 启动编排：三种无效快照形态（缺失/损坏/超期）均不阻断启动——restore 返回 0、空表、gate 照常
 * markReady（三分支 ③：均无有效数据源直接启动成功，空集群死锁不可能）。
 */
class RegistryBootstrapTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    @TempDir
    Path tempDir;

    @Test
    void should_be_ready_with_missing_snapshot() {
        Result result = bootstrap(tempDir.resolve("absent.bin"));

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    @Test
    void should_be_ready_with_corrupt_snapshot() throws IOException {
        Path file = tempDir.resolve("snap.bin");
        Files.write(file, new byte[] {1, 2, 3});

        Result result = bootstrap(file);

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    @Test
    void should_be_ready_with_stale_snapshot() throws IOException {
        Path file = tempDir.resolve("snap.bin");
        // 时间戳 0（1970）：距 now 远超 maxAge 1h → 超期无效
        byte[] stale = new RegistrySnapshotCodec().encode(List.of(new ServiceInstances(KEY, List.of(instance()))), 0L);
        Files.write(file, stale);

        Result result = bootstrap(file);

        assertThat(result.gate().isReady()).isTrue();
        assertThat(result.service().getInstances(KEY)).isEmpty();
    }

    private Result bootstrap(Path snapshotFile) {
        MonotonicClock monotonicClock = () -> System.nanoTime() / 1_000_000;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RegistrySnapshotStore store = new FileRegistrySnapshotStore(
                TestProperties.of(Map.of("artemis.registry.snapshot.path", snapshotFile.toString()))
                        .properties());
        RegistryService service = new RegistryServiceImpl(
                TestProperties.empty(),
                Clock.systemUTC(),
                monotonicClock,
                change -> {},
                new SelfProtection(
                        TestProperties.of(Map.of(
                                        SelfProtection.KEY_WINDOW_SECONDS,
                                        "10",
                                        SelfProtection.KEY_MIN_COUNT,
                                        "50",
                                        SelfProtection.KEY_FACTOR,
                                        "0.85"))
                                .properties(),
                        meters,
                        monotonicClock),
                store,
                meters);
        ReadinessGate gate = new ReadinessGate();
        new RegistryBootstrap(service, gate).afterPropertiesSet();
        return new Result(service, gate);
    }

    private static Instance instance() {
        return new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, java.util.Map.of());
    }

    private record Result(RegistryService service, ReadinessGate gate) {}
}
