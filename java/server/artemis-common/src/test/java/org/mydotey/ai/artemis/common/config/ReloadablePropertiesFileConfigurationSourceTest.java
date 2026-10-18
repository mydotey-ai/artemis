package org.mydotey.ai.artemis.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.StringProperties;

class ReloadablePropertiesFileConfigurationSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void should_reload_and_raise_change_event_when_file_changes() throws Exception {
        Path file = tempDir.resolve("artemis.properties");
        Files.writeString(file, "test.dynamic=1000");

        try (ReloadablePropertiesFileConfigurationSource source =
                new ReloadablePropertiesFileConfigurationSource(file)) {
            StringProperties properties =
                    new StringProperties(ConfigurationManagers.newManager("test", Map.of(1, source)));
            Property<String, String> property = properties.getStringProperty("test.dynamic", "default");

            assertThat(property.getValue()).isEqualTo("1000");

            Files.writeString(file, "test.dynamic=2000");

            await("dynamic property reloaded", () -> "2000".equals(property.getValue()));
        }
    }

    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timeout waiting: " + description);
    }
}
