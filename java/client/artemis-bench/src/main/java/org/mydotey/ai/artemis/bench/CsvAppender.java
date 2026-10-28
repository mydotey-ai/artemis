package org.mydotey.ai.artemis.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** CSV 追加器：建文件写表头，行级 append + flush（驱动/采样/事件 CSV 共用）。 */
final class CsvAppender implements AutoCloseable {

    private final BufferedWriter writer;

    CsvAppender(Path path, String header) throws IOException {
        writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        writer.write(header);
        writer.newLine();
        writer.flush();
    }

    /** 写一行并立即落盘（跨进程 join 与采样断点可读）。IO 失败降级为 stderr（压测不因单行丢数据中止）。 */
    synchronized void append(String row) {
        try {
            writer.write(row);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("csv write failed: " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
