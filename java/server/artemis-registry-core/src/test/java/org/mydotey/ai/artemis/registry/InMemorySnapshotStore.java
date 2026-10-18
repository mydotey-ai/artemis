package org.mydotey.ai.artemis.registry;

import java.util.Optional;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;

/** 测试设施：内存快照 store（单测与 IT 隔离文件系统用）。 */
public final class InMemorySnapshotStore implements RegistrySnapshotStore {

    private volatile byte[] snapshot;

    @Override
    public Optional<byte[]> read() {
        return Optional.ofNullable(snapshot);
    }

    @Override
    public void write(byte[] snapshot) {
        this.snapshot = snapshot;
    }
}
