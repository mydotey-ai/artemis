package org.mydotey.ai.artemis.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 投影视图 checksum（D-F2，ADR-019 hash 对账）：canonical 规则跨语言稳定（v1.x Rust SDK
 * 按 api 文档同一规则独立实现），不依赖 protobuf 序列化字节与 map 迭代序。
 *
 * <p>规则：per-instance hash + XOR 结合——实例集与 metadata 均无排序依赖，唯一顺序敏感点
 * 是 instance 内标量字段的位置序列（固定顺序，无歧义）；空实例集 = 0。字段分隔符
 * {@code U+0000}（值含 U+0000 不受支持）；status 以语义名（UNSPECIFIED/UNKNOWN/UP/DOWN）
 * 编码，port/weight 十进制。规则全文单一来源在 api/discovery-reconciliation-api.md。
 *
 * <p>ponytail: 32 位指纹（ADR-019「4 字节级」既定），不同内容碰撞概率 ~2^-32，
 * instanceId 唯一无重复元素消没；冲突由 SDK 15min 全量刷新兜底。
 */
public final class ProjectionChecksum {

    private static final char SEPARATOR = '\u0000';
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private ProjectionChecksum() {}

    /** 服务实例集视图指纹：per-instance hash 的 XOR 结合（空集 = 0）。 */
    public static int of(ServiceInstances service) {
        int checksum = 0;
        for (Instance instance : service.instances()) {
            checksum ^= of(instance);
        }
        return checksum;
    }

    /** 单实例指纹：标量字段位置序列的 FNV-1a-32 ^ metadata 键值对 hash 的 XOR。 */
    public static int of(Instance instance) {
        StringBuilder canonical = new StringBuilder(64);
        canonical
                .append(instance.instanceId())
                .append(SEPARATOR)
                .append(instance.host())
                .append(SEPARATOR)
                .append(instance.port())
                .append(SEPARATOR)
                .append(instance.status().name())
                .append(SEPARATOR)
                .append(instance.weight())
                .append(SEPARATOR);
        int metadata = 0;
        for (Map.Entry<String, String> entry : instance.metadata().entrySet()) {
            metadata ^= fnv1a32(entry.getKey() + SEPARATOR + entry.getValue() + SEPARATOR);
        }
        return fnv1a32(canonical.toString()) ^ metadata;
    }

    private static int fnv1a32(String value) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff; // 无符号字节（标准 FNV；跨语言一致的前提）
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
