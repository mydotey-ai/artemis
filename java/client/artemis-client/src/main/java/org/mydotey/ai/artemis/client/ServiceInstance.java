package org.mydotey.ai.artemis.client;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 服务实例：SDK 注册侧的上报单元（客户端自报，服务端只存事实）。不可变。
 */
public final class ServiceInstance {

    private final ServiceId service;
    private final String instanceId;
    private final String host;
    private final int port;
    private final InstanceStatus status;
    private final int weight;
    private final Map<String, String> metadata;

    private ServiceInstance(Builder builder) {
        this.service = builder.service;
        this.instanceId = builder.instanceId;
        this.host = builder.host;
        this.port = builder.port;
        this.status = builder.status == null ? InstanceStatus.UP : builder.status;
        this.weight = builder.weight;
        this.metadata = builder.metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(builder.metadata));
    }

    public ServiceId getService() {
        return service;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    /** LB 权重；≤ 0 等同未设置，服务端入口归一为 100。 */
    public int getWeight() {
        return weight;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceInstance)) {
            return false;
        }
        ServiceInstance that = (ServiceInstance) o;
        return port == that.port
                && weight == that.weight
                && Objects.equals(service, that.service)
                && Objects.equals(instanceId, that.instanceId)
                && Objects.equals(host, that.host)
                && status == that.status
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, instanceId, host, port, status, weight, metadata);
    }

    @Override
    public String toString() {
        return service + "/" + instanceId + "@" + host + ":" + port + "(" + status + ")";
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {

        private ServiceId service;
        private String instanceId;
        private String host;
        private int port;
        private InstanceStatus status;
        private int weight;
        private Map<String, String> metadata;

        public Builder service(ServiceId service) {
            this.service = service;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder status(InstanceStatus status) {
            this.status = status;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ServiceInstance build() {
            Objects.requireNonNull(service, "service");
            ServiceId.requireNonBlank(instanceId, "instanceId");
            ServiceId.requireNonBlank(host, "host");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port must be in (0, 65535]");
            }
            if (metadata != null) {
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    ServiceId.requireNonBlank(entry.getKey(), "metadata key");
                    Objects.requireNonNull(entry.getValue(), "metadata value for '" + entry.getKey() + "'");
                }
            }
            return new ServiceInstance(this);
        }
    }
}
