package org.mydotey.ai.artemis.management.server.grpc;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * registry 只读查询客户端配置（v0.2 集群地址列表另行演进）。
 *
 * <p>端点单一配置 `artemis.management.registry.endpoint`（host:port）：**无默认值**，未配置时
 * 视为上游未就绪（调用返回 REGISTRY_UNAVAILABLE），不假设本机地址。
 * 凭证与单次查询 deadline 为**动态配置**：运行期可改 username/password（认证轮换）与 timeout。
 * 内部认证默认关闭，故凭证默认空——username 非空时才附加 Basic 认证。
 */
public final class RegistryClientConfig {

    static final String KEY_ENDPOINT = "artemis.management.registry.endpoint";
    static final String KEY_USERNAME = "artemis.management.registry.username";
    static final String KEY_PASSWORD = "artemis.management.registry.password";
    static final String KEY_TIMEOUT_MS = "artemis.management.registry.timeoutMs";

    private static final String DEFAULT_ENDPOINT = "";
    private static final String DEFAULT_USERNAME = "";
    private static final String DEFAULT_PASSWORD = "";
    private static final long DEFAULT_TIMEOUT_MS = 3_000;

    private final Property<String, String> endpoint;
    private final Property<String, String> username;
    private final Property<String, String> password;
    private final Property<String, Long> timeoutMs;

    public RegistryClientConfig(StringProperties properties) {
        this.endpoint = properties.getStringProperty(KEY_ENDPOINT, DEFAULT_ENDPOINT);
        this.username = properties.getStringProperty(KEY_USERNAME, DEFAULT_USERNAME);
        this.password = properties.getStringProperty(KEY_PASSWORD, DEFAULT_PASSWORD);
        this.timeoutMs =
                properties.getLongProperty(KEY_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_TIMEOUT_MS, 1L, 60_000L));
    }

    public boolean isConfigured() {
        return endpoint.getValue() != null && !endpoint.getValue().isBlank();
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
