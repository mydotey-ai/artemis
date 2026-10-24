package org.mydotey.ai.artemis.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 服务发现选择器（不可变，订阅时 API 参数传入）：语义与服务端 selector 一致——
 * services 空集合 = 全部服务；labels 空 map = 不过滤；clusters 空集合 = 全部来源（union）。
 * 同内容选择器的指纹相同，跨订阅共享磁盘快照文件。
 */
public final class DiscoverySelector {

    private final Set<ServiceId> services;
    private final Map<String, String> labels;
    private final Set<String> clusters;
    private final String fingerprint;

    private DiscoverySelector(Builder builder) {
        this.services = Collections.unmodifiableSet(new LinkedHashSet<>(builder.services));
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(builder.labels));
        this.clusters = Collections.unmodifiableSet(new LinkedHashSet<>(builder.clusters));
        this.fingerprint = computeFingerprint();
    }

    /** 精确订阅的服务集合；空 = 全部服务。 */
    public Set<ServiceId> services() {
        return services;
    }

    /** 实例 metadata 全键值过滤（服务端翻译为视图语义）；空 = 不过滤。 */
    public Map<String, String> labels() {
        return labels;
    }

    /** 来源 cluster 精确集合；空 = 全部（union）。 */
    public Set<String> clusters() {
        return clusters;
    }

    /** canonical 指纹（规范化拼接的 FNV-1a-32，8 位 16 进制串）；快照文件与指标标签用。 */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * canonical 指纹（FNV-1a-64，16 位 16 进制）：与 equals 一致——service/label/cluster 均排序后
     * 以「长度前缀 + 值」编码（无分隔符歧义，如 {a:"b=c"} 与 {"a=b":"c"} 不会碰撞）。
     */
    private String computeFingerprint() {
        StringBuilder canonical = new StringBuilder(64);
        List<String> serviceKeys = new ArrayList<>(services.size());
        for (ServiceId service : services) {
            serviceKeys.add(service.getNamespace() + "/" + service.getService());
        }
        Collections.sort(serviceKeys);
        canonical.append('S');
        for (String key : serviceKeys) {
            appendField(canonical, key);
        }
        canonical.append('L');
        for (Map.Entry<String, String> entry : new TreeMap<>(labels).entrySet()) {
            appendField(canonical, entry.getKey());
            appendField(canonical, entry.getValue());
        }
        canonical.append('C');
        List<String> sortedClusters = new ArrayList<>(clusters);
        Collections.sort(sortedClusters);
        for (String cluster : sortedClusters) {
            appendField(canonical, cluster);
        }

        long hash = 0xcbf29ce484222325L; // FNV-1a 64 offset basis
        for (byte b : canonical.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L; // FNV prime
        }
        return String.format("%016x", hash);
    }

    /** 长度前缀（无歧义字段编码）。 */
    private static void appendField(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscoverySelector)) {
            return false;
        }
        DiscoverySelector that = (DiscoverySelector) o;
        return services.equals(that.services) && labels.equals(that.labels) && clusters.equals(that.clusters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(services, labels, clusters);
    }

    @Override
    public String toString() {
        return "selector{services=" + services.size() + ", labels=" + labels + ", clusters=" + clusters + "}";
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {

        private final Set<ServiceId> services = new LinkedHashSet<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Set<String> clusters = new LinkedHashSet<>();

        public Builder addService(ServiceId service) {
            Objects.requireNonNull(service, "service");
            services.add(service);
            return this;
        }

        public Builder services(Set<ServiceId> services) {
            Objects.requireNonNull(services, "services");
            this.services.clear();
            this.services.addAll(services);
            return this;
        }

        public Builder putLabel(String key, String value) {
            requireNonBlank(key, "label key");
            requireNonBlank(value, "label value");
            labels.put(key, value);
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            Objects.requireNonNull(labels, "labels");
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                putLabel(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public Builder addCluster(String cluster) {
            requireNonBlank(cluster, "cluster");
            clusters.add(cluster.trim());
            return this;
        }

        public Builder clusters(Set<String> clusters) {
            Objects.requireNonNull(clusters, "clusters");
            for (String cluster : clusters) {
                addCluster(cluster);
            }
            return this;
        }

        public DiscoverySelector build() {
            return new DiscoverySelector(this);
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
