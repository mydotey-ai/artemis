package org.mydotey.ai.artemis.registry.infrastructure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;

/**
 * 领域端口 {@link RegistrySnapshotStore} 文件实现：单快照文件（最近一次成功落盘）。
 * 原子写：同目录唯一命名临时文件 → fsync 数据 → {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)}
 * → fsync 目录（崩溃时要么旧快照完整、要么新快照完整，无半写状态；不支持原子 rename 的
 * 文件系统降级为普通 move）。读失败按无快照处理（不抛出——上层语义为空表启动，
 * 不可读与缺失分别记日志）。字节校验（magic/长度/条目）归 application 编解码。
 */
@Slf4j
public final class FileRegistrySnapshotStore implements RegistrySnapshotStore {

    private final Path file;

    public FileRegistrySnapshotStore(Path file) {
        this.file = file;
    }

    @Override
    public Optional<byte[]> read() {
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            // 文件存在但不可读（权限/磁盘错误）：与缺失区分，避免上层「no snapshot found」误导诊断
            log.warn("snapshot file unreadable, treated as missing: {}", file, e);
            return Optional.empty();
        }
    }

    @Override
    public void write(byte[] snapshot) {
        Path target = file.toAbsolutePath(); // 裸文件名时 getParent() 为 null
        Path dir = target.getParent();
        Path temp = dir.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(dir);
            try (FileChannel channel =
                    FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(snapshot);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true); // 数据落盘后再发布，保证 rename 后的文件内容完整
            }
            moveIntoPlace(temp, target);
            fsyncDirectory(dir);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不掩盖原始错误
            }
            throw new IllegalStateException("snapshot write failed: " + target, e);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // NFS 等不支持原子 rename 的文件系统：降级，代价仅是崩溃窗口内丢原子性
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** fsync 目录使 rename 持久（防崩溃后丢目录项）；失败降级为日志——最坏回退到上一份快照。 */
    private void fsyncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            log.debug("snapshot directory fsync not supported or failed: {}", dir, e);
        }
    }
}
