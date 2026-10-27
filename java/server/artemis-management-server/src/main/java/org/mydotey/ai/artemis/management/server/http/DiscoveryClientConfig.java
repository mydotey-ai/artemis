package org.mydotey.ai.artemis.management.server.http;

import java.util.Arrays;
import java.util.List;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * discovery 订阅关系查询客户端配置（v0.5 phase-5，console 订阅视图数据源）。
 *
 * <p>全部**静态**（启动后不变）：endpoints（逗号分隔 {@code host:port}，0..N，空 = 视图无数据
 * 自解释、不报错）、可选 Basic 凭证（discovery {@code /admin/*} 认证启用时配置，username
 * 非空才发送——discovery 侧认证默认关闭）、单源请求超时。命名与 discovery 侧
 * {@code artemis.discovery.management.*} 对称。
 */
public final class DiscoveryClientConfig {

    static final String KEY_ENDPOINTS = "artemis.management.discovery.endpoints";
    static final String KEY_USERNAME = "artemis.management.discovery.auth.username";
    static final String KEY_PASSWORD = "artemis.management.discovery.auth.password";
    static final String KEY_TIMEOUT_MS = "artemis.management.discovery.timeoutMs";

    private static final String DEFAULT_ENDPOINTS = "";
    private static final String DEFAULT_USERNAME = "";
    private static final String DEFAULT_PASSWORD = "";
    private static final long DEFAULT_TIMEOUT_MS = 3_000;

    private final Property<String, String> endpoints;
    private final Property<String, String> username;
    private final Property<String, String> password;
    private final Property<String, Long> timeoutMs;

    public DiscoveryClientConfig(StringProperties properties) {
        this.endpoints =
                ScfProperties.staticString(properties, KEY_ENDPOINTS, DEFAULT_ENDPOINTS, "discovery HTTP 端点列表");
        this.username = ScfProperties.staticString(
                properties, KEY_USERNAME, DEFAULT_USERNAME, "discovery /admin 认证用户名（空 = 不发送认证）");
        this.password = ScfProperties.staticString(properties, KEY_PASSWORD, DEFAULT_PASSWORD, "discovery /admin 认证密码");
        this.timeoutMs =
                ScfProperties.staticLong(properties, KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS, 100L, 60_000L, "单源请求超时");
    }

    /** 解析后的端点列表（配置序，逗号分隔、trim、忽略空段）。 */
    public List<String> endpoints() {
        String value = endpoints.getValue();
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(endpoint -> !endpoint.isEmpty())
                .toList();
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
