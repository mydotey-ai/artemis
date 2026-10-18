package org.mydotey.ai.artemis.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.registry.TestProperty;

/** 文件快照 store：原子写（tmp + move）后读回一致、临时文件不残留、缺失文件返回 empty。 */
class FileRegistrySnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void should_write_atomically_and_read_back() throws IOException {
        Path file = tempDir.resolve("nested/registry-snapshot.bin"); // 父目录不存在：write 侧自建
        FileRegistrySnapshotStore store = store(file);

        store.write(new byte[] {1, 2, 3, 4, 5});
        store.write(new byte[] {9}); // 覆盖写：单快照文件语义

        Optional<byte[]> read = store.read();
        assertThat(read).contains(new byte[] {9});
        assertThat(Files.exists(file)).isTrue();
        try (Stream<Path> files = Files.list(file.getParent())) {
            // 临时文件不残留（move 落位）
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isEmpty();
        }
    }

    @Test
    void should_return_empty_when_file_missing() {
        FileRegistrySnapshotStore store = store(tempDir.resolve("absent.bin"));

        assertThat(store.read()).isEmpty();
    }

    @Test
    void should_write_bare_relative_filename_without_parent() throws IOException {
        // 裸文件名（无父目录）：目标路径先绝对化，否则 getParent() 为 null → NPE → 快照永不落盘
        Path bare = Path.of("registry-snapshot-" + java.util.UUID.randomUUID() + ".bin");
        try {
            FileRegistrySnapshotStore store = store(bare);

            store.write(new byte[] {7, 8, 9});

            assertThat(store.read()).contains(new byte[] {7, 8, 9});
        } finally {
            Files.deleteIfExists(bare);
        }
    }

    @Test
    void should_not_publish_torn_snapshot_under_concurrent_writes() {
        Path file = tempDir.resolve("concurrent.bin");
        FileRegistrySnapshotStore store = store(file);
        byte[] first = payload((byte) 1);
        byte[] second = payload((byte) 2);

        java.util.stream.IntStream.range(0, 100).parallel().forEach(i -> store.write(i % 2 == 0 ? first : second));

        // 唯一临时名 + 原子发布：任何时刻读到的都是某个完整载荷，绝不是两者撕裂
        byte[] actual = store.read().orElseThrow();
        assertThat(java.util.Arrays.equals(actual, first) || java.util.Arrays.equals(actual, second))
                .isTrue();
    }

    private static byte[] payload(byte value) {
        byte[] bytes = new byte[1024];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static FileRegistrySnapshotStore store(Path file) {
        return new FileRegistrySnapshotStore(TestProperty.fixed(file.toString()));
    }
}
