package org.mydotey.ai.artemis.management.server.auth;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * management 外部 HTTP 单账户认证配置（ADR-009：默认开启，显式配置方可关闭）。
 * 配置项在本类内定义一次，HTTP 过滤器共享该配置对象。
 */
@Slf4j
public final class ManagementAuthConfig {

    static final String KEY_ENABLED = "artemis.management.auth.enabled";
    static final String KEY_USERNAME = "artemis.management.auth.username";
    static final String KEY_PASSWORD = "artemis.management.auth.password";

    private static final String DEFAULT_USERNAME = "artemis";
    private static final String DEFAULT_PASSWORD = "artemis";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public ManagementAuthConfig(StringProperties properties) {
        this.enabled = ScfProperties.staticBoolean(properties, KEY_ENABLED, true, "authentication enabled (static)");
        this.username = ScfProperties.staticString(
                properties, KEY_USERNAME, DEFAULT_USERNAME, "authentication username (static)");
        this.password = ScfProperties.staticString(
                properties, KEY_PASSWORD, DEFAULT_PASSWORD, "authentication password (static)");
        // 防御性校验：SCF 把空白视为未设置并回落默认值，正常配置路径不会触发
        if (enabled() && username().isBlank()) {
            throw new IllegalArgumentException(KEY_USERNAME + " must not be blank when auth enabled");
        }
        if (enabled() && password().isBlank()) {
            throw new IllegalArgumentException(KEY_PASSWORD + " must not be blank when auth enabled");
        }
        if (!enabled()) {
            log.warn("management authentication is disabled by configuration ({}=false)", KEY_ENABLED);
        } else if (DEFAULT_PASSWORD.equals(password())) {
            log.warn(
                    "management authentication is enabled with the default password, change {} for production",
                    KEY_PASSWORD);
        }
    }

    public boolean enabled() {
        return enabled.getValue();
    }

    public String username() {
        return username.getValue();
    }

    public String password() {
        return password.getValue();
    }
}
