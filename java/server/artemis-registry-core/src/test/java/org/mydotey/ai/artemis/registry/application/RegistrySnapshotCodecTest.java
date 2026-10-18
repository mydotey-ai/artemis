package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 快照容器编解码：domain → bytes（含时间戳）→ domain 往返等价；损坏输入统一抛 IllegalArgumentException。 */
class RegistrySnapshotCodecTest {

    private final RegistrySnapshotCodec codec = new RegistrySnapshotCodec();

    @Test
    void should_roundtrip_snapshot_bytes() {
        List<ServiceInstances> snapshot = List.of(
                new ServiceInstances(
                        new ServiceKey("default", "order-service"),
                        List.of(
                                new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("zone", "a")),
                                new Instance("i2", "10.0.0.2", 8081, InstanceStatus.DOWN, 200, Map.of()))),
                new ServiceInstances(
                        new ServiceKey("prod", "pay-service"),
                        List.of(new Instance("p1", "10.1.0.1", 9090, InstanceStatus.UP, 100, Map.of()))));

        byte[] bytes = codec.encode(snapshot, 1_700_000_000_000L);

        RegistrySnapshotCodec.DecodedSnapshot decoded = codec.decode(bytes);

        assertThat(decoded.timestampMs()).isEqualTo(1_700_000_000_000L);
        assertThat(decoded.services()).isEqualTo(snapshot); // record 值等价（含 metadata/status/weight）
    }

    @Test
    void should_discard_corrupt_snapshot_on_read() {
        List<ServiceInstances> snapshot =
                List.of(new ServiceInstances(new ServiceKey("default", "order-service"), List.of(instance())));

        assertThatThrownBy(() -> codec.decode(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot");

        byte[] bytes = codec.encode(snapshot, 0L);
        assertThatThrownBy(() -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1))) // 截尾
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot");

        byte[] badMagic = bytes.clone();
        badMagic[0] = 'X';
        assertThatThrownBy(() -> codec.decode(badMagic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");

        byte[] trailing = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, trailing, 0, bytes.length);
        assertThatThrownBy(() -> codec.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    void should_reject_corrupt_lengths_without_allocating() {
        List<ServiceInstances> snapshot =
                List.of(new ServiceInstances(new ServiceKey("default", "order-service"), List.of(instance())));
        byte[] bytes = codec.encode(snapshot, 0L);

        // 条目数（magic 7B + version 1B + timestamp 8B 之后）改为极大值：须在分配前拒绝（D6，不得 OOME）
        byte[] hugeCount = bytes.clone();
        java.nio.ByteBuffer.wrap(hugeCount).putInt(16, Integer.MAX_VALUE);
        assertThatThrownBy(() -> codec.decode(hugeCount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry count");

        // 首条目 varint 长度前缀改为 0x7FFFFFFF（超剩余字节）：delimited 解析拒绝，不得分配
        byte[] hugeLength = bytes.clone();
        int entryStart = 20;
        hugeLength[entryStart] = (byte) 0xFF;
        hugeLength[entryStart + 1] = (byte) 0xFF;
        hugeLength[entryStart + 2] = (byte) 0xFF;
        hugeLength[entryStart + 3] = (byte) 0xFF;
        hugeLength[entryStart + 4] = 0x07;
        assertThatThrownBy(() -> codec.decode(hugeLength)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Instance instance() {
        return new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("zone", "a"));
    }
}
