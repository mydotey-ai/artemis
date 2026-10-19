package org.mydotey.ai.artemis.client;

import java.util.Objects;

/**
 * 服务标识：namespace（多租户隔离）+ service 名。不可变。
 */
public final class ServiceId {

    private final String namespace;
    private final String service;

    private ServiceId(Builder builder) {
        this.namespace = builder.namespace;
        this.service = builder.service;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getService() {
        return service;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceId)) {
            return false;
        }
        ServiceId that = (ServiceId) o;
        return Objects.equals(namespace, that.namespace) && Objects.equals(service, that.service);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, service);
    }

    @Override
    public String toString() {
        return namespace + "/" + service;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /** 必填项缺失抛 {@link IllegalArgumentException}（信任边界一次校验）。 */
    public static final class Builder {

        private String namespace;
        private String service;

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public ServiceId build() {
            requireNonBlank(namespace, "namespace");
            requireNonBlank(service, "service");
            return new ServiceId(this);
        }
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
