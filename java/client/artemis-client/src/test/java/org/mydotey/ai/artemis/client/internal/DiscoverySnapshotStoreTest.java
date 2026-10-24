package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.TestProperties;

/** 磁盘快照：回放/指纹校验/损坏忽略/clientId 目录隔离/异步 writer。 */
class DiscoverySnapshotStoreTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order").build();
    private static final String DIR_KEY = "artemis.client.discovery.snapshot.dir";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(DIR_KEY);
    }

    @Test
    void should_round_trip_and_reject_selector_mismatch_or_corrupt_file() throws Exception {
        System.setProperty(DIR_KEY, tempDir.toString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiscoverySnapshotStore store =
                new DiscoverySnapshotStore(TestProperties.systemProperties(), "client-a", registry);
        DiscoverySelector selector =
                DiscoverySelector.newBuilder().addService(ORDER).build();
        LocalDiscoveryCache.ClusterEntry entry =
                new LocalDiscoveryCache.ClusterEntry("cluster-a", ORDER, Collections.singletonList(instance()));

        long timestamp = store.save(selector, Collections.singletonList(entry), selector.fingerprint());
        assertThat(timestamp).isPositive();

        DiscoverySnapshotStore.Snapshot loaded = store.load(selector, selector.fingerprint());
        assertThat(loaded).isNotNull();
        assertThat(loaded.timestampMs).isEqualTo(timestamp);
        assertThat(loaded.entries).hasSize(1);
        assertThat(loaded.entries.get(0).cluster).isEqualTo("cluster-a");
        assertThat(loaded.entries.get(0).instances)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("i1");

        DiscoverySelector other = DiscoverySelector.newBuilder()
                .addService(ServiceId.newBuilder().namespace("x").service("y").build())
                .build();
        assertThat(store.load(other, other.fingerprint())).isNull();

        Path file = tempDir.resolve("client-a").resolve("selector-" + selector.fingerprint() + ".snapshot");
        Files.write(file, new byte[] {1, 2, 3});
        assertThat(store.load(selector, selector.fingerprint())).isNull();
    }

    @Test
    void should_isolate_snapshot_dirs_by_client_id() {
        System.setProperty(DIR_KEY, tempDir.toString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiscoverySelector selector =
                DiscoverySelector.newBuilder().addService(ORDER).build();
        LocalDiscoveryCache.ClusterEntry entry =
                new LocalDiscoveryCache.ClusterEntry("cluster-a", ORDER, Collections.singletonList(instance()));

        new DiscoverySnapshotStore(TestProperties.systemProperties(), "client-a", registry)
                .save(selector, Collections.singletonList(entry), selector.fingerprint());
        new DiscoverySnapshotStore(TestProperties.systemProperties(), "client-b", registry)
                .save(selector, Collections.singletonList(entry), selector.fingerprint());

        assertThat(tempDir.resolve("client-a")).exists();
        assertThat(tempDir.resolve("client-b")).exists();
    }

    private static ServiceInstance instance() {
        return ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .weight(100)
                .build();
    }
}
