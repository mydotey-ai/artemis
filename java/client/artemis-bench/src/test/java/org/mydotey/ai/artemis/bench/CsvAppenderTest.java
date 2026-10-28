package org.mydotey.ai.artemis.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 采样器 CSV 输出（时间戳 + 指标列），压测数据可信的前提。 */
class CsvAppenderTest {

    @TempDir
    Path tempDir;

    @Test
    void should_emit_csv_rows_when_samples_collected() throws Exception {
        Path csv = tempDir.resolve("samples-test.csv");
        try (CsvAppender appender = new CsvAppender(csv, "timestamp,metric,value")) {
            appender.append("1793175800000,artemis.client.connected,1.0");
            appender.append("1793175805000,artemis.client.instances,50.0");
        }
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines)
                .containsExactly(
                        "timestamp,metric,value",
                        "1793175800000,artemis.client.connected,1.0",
                        "1793175805000,artemis.client.instances,50.0");
    }
}
