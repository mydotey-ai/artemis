package org.mydotey.ai.artemis.client;

import java.util.Objects;

/**
 * 实例标识：定位单个实例（deregister 的操作目标）。不可变。
 */
public final class InstanceId {

    private final ServiceId service;
    private final String instanceId;

    private InstanceId(Builder builder) {
        this.service = builder.service;
        this.instanceId = builder.instanceId;
    }

    public ServiceId getService() {
        return service;
    }

    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InstanceId)) {
            return false;
        }
        InstanceId that = (InstanceId) o;
        return Objects.equals(service, that.service) && Objects.equals(instanceId, that.instanceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, instanceId);
    }

    @Override
    public String toString() {
        return service + "/" + instanceId;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {

        private ServiceId service;
        private String instanceId;

        public Builder service(ServiceId service) {
            this.service = service;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public InstanceId build() {
            Objects.requireNonNull(service, "service");
            ServiceId.requireNonBlank(instanceId, "instanceId");
            return new InstanceId(this);
        }
    }
}
