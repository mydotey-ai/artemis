package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.proto.discovery.v1.ClusterServiceInstances;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponseData;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 磁盘快照（冷启动回放 + server 全挂兜底，架构 §4.1）：每订阅一个文件
 * {@code <dir>/<clientId>/selector-<fingerprint>.snapshot}。payload 复用 discovery.v1
 * QueryResponseData proto bytes（不落上游新鲜度）；带 magic/版本/写入时间/选择器指纹。
 * 回放不设过期（陈旧换可用），指纹不符/损坏忽略且不影响订阅启动。写为临时文件 + 原子替换。
 */
final class DiscoverySnapshotStore {

    static final String KEY_ENABLED = "artemis.client.discovery.snapshot.enabled";
    static final String KEY_DIR = "artemis.client.discovery.snapshot.dir";

    private static final Logger LOG = LoggerFactory.getLogger(DiscoverySnapshotStore.class);

    private static final byte[] MAGIC = "ARTEMIS-DISCOVERY-SNAPSHOT".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    /** 回放产物：条目 + 快照写入时间（epoch 毫秒）。 */
    static final class Snapshot {

        final List<LocalDiscoveryCache.ClusterEntry> entries;
        final long timestampMs;

        Snapshot(List<LocalDiscoveryCache.ClusterEntry> entries, long timestampMs) {
            this.entries = entries;
            this.timestampMs = timestampMs;
        }
    }

    private final Property<String, Boolean> enabled;
    private final Property<String, String> dir;
    private final String clientId;
    private final MeterRegistry registry;

    DiscoverySnapshotStore(StringProperties properties, String clientId, MeterRegistry registry) {
        this.enabled = properties.getBooleanProperty(KEY_ENABLED, true);
        this.dir = ClientProperties.stringProperty(
                properties,
                KEY_DIR,
                "artemis-client-discovery-snapshots",
                "discovery snapshot root dir (per clientId/selector files)");
        this.clientId = clientId;
        this.registry = registry;
    }

    /** 读取本订阅快照；禁用/不存在/损坏/指纹不符返回 null（计数并记录原因）。 */
    Snapshot load(DiscoverySelector selector, String subscriptionTag) {
        if (!Boolean.TRUE.equals(enabled.getValue())) {
            return null;
        }
        Path file = file(selector);
        if (!Files.isRegularFile(file)) {
            snapshot(selector, subscriptionTag, "load-miss");
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("bad magic");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            long timestamp = in.readLong();
            String fingerprint = in.readUTF();
            if (!selector.fingerprint().equals(fingerprint)) {
                LOG.info(
                        "discovery snapshot {} belongs to selector {} (current {}), ignored",
                        file,
                        fingerprint,
                        selector.fingerprint());
                snapshot(selector, subscriptionTag, "load-invalid");
                return null;
            }
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES) {
                throw new IOException("invalid snapshot payload length " + length);
            }
            byte[] body = new byte[length];
            in.readFully(body);
            Snapshot result = new Snapshot(ProtoMapper.clusterEntries(QueryResponseData.parseFrom(body)), timestamp);
            snapshot(selector, subscriptionTag, "load-hit");
            LOG.info("discovery snapshot loaded from {} ({} entry(ies))", file, result.entries.size());
            return result;
        } catch (Exception e) {
            LOG.warn("discovery snapshot {} invalid, ignored: {}", file, e.toString());
            snapshot(selector, subscriptionTag, "load-invalid");
            return null;
        }
    }

    /** 写本订阅快照（异步 writer/close 终写调用）；返回写入时间戳，禁用/失败返回 0。 */
    long save(DiscoverySelector selector, List<LocalDiscoveryCache.ClusterEntry> entries, String subscriptionTag) {
        if (!Boolean.TRUE.equals(enabled.getValue())) {
            return 0L;
        }
        Path file = file(selector);
        Path temp = null;
        long timestamp = System.currentTimeMillis();
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "snapshot-", ".tmp");
            QueryResponseData.Builder payload = QueryResponseData.newBuilder();
            for (LocalDiscoveryCache.ClusterEntry entry : entries) {
                payload.addServices(ClusterServiceInstances.newBuilder()
                        .setCluster(entry.cluster)
                        .setInstances(ProtoMapper.serviceInstances(entry.service, entry.instances)));
            }
            byte[] body = payload.build().toByteArray();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.write(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(timestamp);
                out.writeUTF(selector.fingerprint());
                out.writeInt(body.length);
                out.write(body);
            }
            move(temp, file);
            snapshot(selector, subscriptionTag, "save-success");
            return timestamp;
        } catch (Exception e) {
            LOG.warn("discovery snapshot save failed for {}: {}", file, e.toString());
            snapshot(selector, subscriptionTag, "save-failure");
            return 0L;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path file(DiscoverySelector selector) {
        String root = dir.getValue();
        Path directory = Paths.get(
                        root == null || root.trim().isEmpty() ? "artemis-client-discovery-snapshots" : root.trim())
                .toAbsolutePath()
                .resolve(clientId);
        return directory.resolve("selector-" + selector.fingerprint() + ".snapshot");
    }

    private void snapshot(DiscoverySelector selector, String subscriptionTag, String outcome) {
        registry.counter(
                        "artemis.client.discovery.snapshot",
                        "client",
                        clientId,
                        "subscription",
                        subscriptionTag,
                        "outcome",
                        outcome)
                .increment();
    }
}
