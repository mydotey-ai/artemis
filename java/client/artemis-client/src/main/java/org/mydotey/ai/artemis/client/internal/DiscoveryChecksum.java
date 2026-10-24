package org.mydotey.ai.artemis.client.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.client.ServiceInstance;

/**
 * 本地视图 checksum（canonical 规则照 api/discovery-reconciliation-api.md §3 独立实现，
 * 不抄服务端代码、不依赖 protobuf 序列化字节；golden vector 单测锚定跨实现一致）：
 * per-instance FNV-1a-32 的 XOR 结合（空集 = 0）；实例集与 metadata 双重抗序（迭代序无关）。
 * statusName 按 wire 语义名原值（UNSPECIFIED/UNKNOWN/UP/DOWN），不做 UP 归一——否则与服务端永久不一致。
 */
final class DiscoveryChecksum {

    private static final char SEPARATOR = '\u0000';
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private DiscoveryChecksum() {}

    /** 服务实例集指纹：per-instance hash 的 XOR。 */
    static int of(List<ServiceInstance> instances) {
        int checksum = 0;
        for (ServiceInstance instance : instances) {
            checksum ^= of(instance);
        }
        return checksum;
    }

    private static int of(ServiceInstance instance) {
        StringBuilder canonical = new StringBuilder(64);
        canonical
                .append(instance.getInstanceId())
                .append(SEPARATOR)
                .append(instance.getHost())
                .append(SEPARATOR)
                .append(instance.getPort())
                .append(SEPARATOR)
                .append(instance.getStatus().name())
                .append(SEPARATOR)
                .append(instance.getWeight())
                .append(SEPARATOR);
        int metadata = 0;
        for (Map.Entry<String, String> entry : instance.getMetadata().entrySet()) {
            metadata ^= fnv1a32(entry.getKey() + SEPARATOR + entry.getValue() + SEPARATOR);
        }
        return fnv1a32(canonical.toString()) ^ metadata;
    }

    private static int fnv1a32(String value) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
