package org.mydotey.ai.artemis.management.server.grpc;

import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * registry 只读查询客户端配置（静态：启动后固化；v0.2 集群地址列表另行演进）。
 * 配置项在本类内定义一次（guidelines §10）。
 */
public final class RegistryClientConfig {

    static final String KEY_ENDPOINT = "artemis.management.registry.endpoint";
    static final String KEY_USERNAME = "artemis.management.registry.username";
    static final String KEY_PASSWORD = "artemis.management.registry.password";
    static final String KEY_TIMEOUT_MS = "artemis.management.registry.timeoutMs";

    private static final String DEFAULT_ENDPOINT = "localhost:9090";
    private static final String DEFAULT_USERNAME = "artemis";
    private static final String DEFAULT_PASSWORD = "artemis";
    private static final long DEFAULT_TIMEOUT_MS = 3_000;

    private final Property<String, String> endpoint;
    private final Property<String, String> username;
    private final Property<String, String> password;
    private final Property<String, Long> timeoutMs;

    public RegistryClientConfig(StringProperties properties) {
        this.endpoint = ScfProperties.staticString(
                properties, KEY_ENDPOINT, DEFAULT_ENDPOINT, "registry gRPC endpoint host:port (static)");
        this.username = ScfProperties.staticString(
                properties, KEY_USERNAME, DEFAULT_USERNAME, "registry basic auth username (static)");
        this.password = ScfProperties.staticString(
                properties, KEY_PASSWORD, DEFAULT_PASSWORD, "registry basic auth password (static)");
        this.timeoutMs = ScfProperties.staticLong(
                properties, KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS, 1, 60_000, "registry query deadline ms (static)");
        // 防御性校验：SCF 把空白视为未设置并回落默认值，正常配置路径不会触发
        if (endpoint().isBlank()) {
            throw new IllegalArgumentException(KEY_ENDPOINT + " must not be blank");
        }
        if (username().isBlank()) {
            throw new IllegalArgumentException(KEY_USERNAME + " must not be blank");
        }
        if (password().isBlank()) {
            throw new IllegalArgumentException(KEY_PASSWORD + " must not be blank");
        }
    }

    public String endpoint() {
        return endpoint.getValue();
    }

    public String username() {
        return username.getValue();
    }

    public String password() {
        return password.getValue();
    }

    public long timeoutMs() {
        return timeoutMs.getValue();
    }
}
