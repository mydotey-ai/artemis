package org.mydotey.ai.artemis.registry.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;

/**
 * 快照容器编解码（D3/D10）：magic "ARTSNAP" + 1B 格式版本(1) + 8B 落盘时间戳（epoch millis，
 * 墙钟——跨重启比较，单调钟仅进程内可比）+ 4B 条目数 + 逐条 [protobuf delimited 帧
 * （varint 长度前缀 + proto ServiceInstances bytes）]。
 *
 * <p>快照是服务端内部持久化格式而非 wire 契约：格式版本只管容器结构，条目演化靠既有契约消息
 * （proto common.v1 ServiceInstances）的字段兼容——不新增契约消息，冻结组不动。同名类以包名
 * 区分（ADR-015）：domain 侧 import，proto 侧全限定。
 *
 * <p>损坏输入（长度不足/magic 不符/版本不符/条目数不一致/尾部残留）统一抛
 * {@link IllegalArgumentException}，由调用方按「丢弃 + WARN + 空表启动」处置（D6）。
 */
public final class RegistrySnapshotCodec {

    private static final byte[] MAGIC = "ARTSNAP".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;

    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    /** 编码：domain 全量快照 + 落盘时间戳 → bytes。 */
    public byte[] encode(List<ServiceInstances> services, long timestampMs) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeLong(timestampMs);
            out.writeInt(services.size());
            for (ServiceInstances service : services) {
                mapper.toProto(service).writeDelimitedTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot encode failed", e); // 内存流，实际不可达
        }
        return buffer.toByteArray();
    }

    /** 解码：bytes → 时间戳 + domain 快照；损坏抛 IllegalArgumentException。 */
    public DecodedSnapshot decode(byte[] bytes) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw corrupt("bad magic");
            }
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw corrupt("unsupported format version " + version);
            }
            long timestampMs = in.readLong();
            int entryCount = in.readInt();
            // 每条目至少 1 字节（delimited 帧的 varint 长度前缀），条目数超过剩余字节必为损坏：
            // 先于任何分配校验，避免损坏长度/条目数触发 OOME 穿透为拒绝启动（D6）
            if (entryCount < 0 || entryCount > in.available()) {
                throw corrupt("invalid entry count " + entryCount + " for " + in.available() + " bytes");
            }
            List<ServiceInstances> services = new ArrayList<>(Math.min(entryCount, 256));
            for (int i = 0; i < entryCount; i++) {
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances entry =
                        org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.parseDelimitedFrom(in);
                if (entry == null) {
                    throw corrupt("truncated entry " + i);
                }
                services.add(mapper.toDomain(entry));
            }
            if (in.available() > 0) {
                throw corrupt(in.available() + " trailing bytes");
            }
            return new DecodedSnapshot(timestampMs, services);
        } catch (IOException e) {
            throw corrupt("truncated snapshot: " + e.getMessage());
        }
    }

    private static IllegalArgumentException corrupt(String reason) {
        return new IllegalArgumentException("corrupt snapshot: " + reason);
    }

    /** 解码结果：落盘时间戳 + 全量 domain 快照。 */
    public record DecodedSnapshot(long timestampMs, List<ServiceInstances> services) {}
}
