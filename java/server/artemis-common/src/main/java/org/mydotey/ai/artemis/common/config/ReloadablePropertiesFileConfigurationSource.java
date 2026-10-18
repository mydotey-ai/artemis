package org.mydotey.ai.artemis.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.ConfigurationSourceConfig;
import org.mydotey.scf.DefaultConfigurationSourceConfig;
import org.mydotey.scf.source.stringproperty.StringPropertyConfigurationSource;

/**
 * 可重载的 properties 文件 source：启动加载一次，之后用 WatchService 监听文件变更并
 * {@code raiseChangeEvent()}，使 SCF 动态 property 真正生效（PaaS ConfigMap 的原子替换
 * 也会产生 CREATE/MODIFY 事件）。文件不可读/格式错误在构造期 fail-fast。
 *
 * <p>监听线程为 daemon；{@link #close()} 中断并释放 WatchService（测试用）。
 */
@Slf4j
public final class ReloadablePropertiesFileConfigurationSource
        extends StringPropertyConfigurationSource<ConfigurationSourceConfig> implements AutoCloseable {

    private final Path file;
    private final Thread watcher;
    private volatile Properties properties;

    public ReloadablePropertiesFileConfigurationSource(Path file) {
        super(new DefaultConfigurationSourceConfig.Builder()
                .setName("propertiesFile")
                .build());
        this.file = file.toAbsolutePath();
        this.properties = loadOrThrow(this.file);
        this.watcher =
                Thread.ofPlatform().daemon(true).name("scf-properties-watcher").unstarted(this::watch);
        this.watcher.start();
    }

    @Override
    public String getPropertyValue(String key) {
        return properties.getProperty(key);
    }

    private void watch() {
        Path dir = file.getParent();
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                boolean changed = key.pollEvents().stream()
                        .map(event -> event.context())
                        .filter(Path.class::isInstance)
                        .map(Path.class::cast)
                        .anyMatch(path -> path.getFileName().equals(file.getFileName()));
                key.reset();
                if (changed) {
                    reload();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // close() 正常退出
        } catch (IOException e) {
            log.warn("properties file watcher stopped, dynamic reload disabled: {}", file, e);
        }
    }

    private void reload() {
        try {
            this.properties = loadOrThrow(file);
            raiseChangeEvent();
            log.info("reloaded properties file: {}", file);
        } catch (RuntimeException e) {
            // 保留上一份有效配置，避免半写/临时损坏导致运行中配置被清空
            log.warn("failed to reload properties file, keeping previous values: {}", file, e);
        }
    }

    private static Properties loadOrThrow(Path file) {
        Properties loaded = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            loaded.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load properties file: " + file, e);
        }
        return loaded;
    }

    @Override
    public void close() {
        watcher.interrupt();
    }
}
