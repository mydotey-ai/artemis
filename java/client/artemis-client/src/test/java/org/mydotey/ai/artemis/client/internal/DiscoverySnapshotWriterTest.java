package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.TestAwait;

/** 快照异步落盘：变更即请求、合并写、只写最新（不阻塞调用方）。 */
class DiscoverySnapshotWriterTest {

    @Test
    void should_write_snapshot_asynchronously_on_view_change() {
        DiscoverySnapshotWriter writer = new DiscoverySnapshotWriter();
        CountingSource source = new CountingSource();
        try {
            writer.request(source);
            writer.request(source);
            writer.request(source);

            TestAwait.await("async snapshot write", 3000, () -> source.writes.get() >= 1);
            assertThat(source.writes.get()).isBetween(1, 2); // 200ms 窗口合并突发

            writer.close();
            assertThat(source.writes.get()).isGreaterThanOrEqualTo(1);
        } finally {
            writer.close();
        }
    }

    private static final class CountingSource implements SnapshotSource {

        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public void writeSnapshot() {
            writes.incrementAndGet();
        }
    }
}
