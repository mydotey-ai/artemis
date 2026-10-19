package org.mydotey.ai.artemis.registry.server.auth;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 单账户认证配置（ADR-009：v0.1 默认开启，显式配置方可关闭）。
 * 认证项在本类内定义一次（Property 字段），gRPC 拦截器与 HTTP 过滤器共享该配置对象。
 */
@Slf4j
public final class AuthConfig {

    static final String KEY_ENABLED = "artemis.registry.auth.enabled";
    static final String KEY_USERNAME = "artemis.registry.auth.username";
    static final String KEY_PASSWORD = "artemis.registry.auth.password";

    private static final String DEFAULT_USERNAME = "artemis";
    private static final String DEFAULT_PASSWORD = "artemis";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public AuthConfig(StringProperties properties) {
        this.enabled = ScfProperties.staticBoolean(properties, KEY_ENABLED, true, "authentication enabled (static)");
        this.username = ScfProperties.staticString(
                properties, KEY_USERNAME, DEFAULT_USERNAME, "authentication username (static)");
        this.password = ScfProperties.staticString(
                properties, KEY_PASSWORD, DEFAULT_PASSWORD, "authentication password (static)");
        // 启动期提示（构造即触发）：认证关闭或仍使用默认密码
        boolean enabledValue = enabled();
        if (!enabledValue) {
            log.warn("registry authentication is disabled by configuration ({}=false)", KEY_ENABLED);
        } else if (DEFAULT_PASSWORD.equals(password())) {
            log.warn(
                    "registry authentication is enabled with the default password, change {} for production",
                    KEY_PASSWORD);
        }
    }

    public boolean enabled() {
        // SCF 内置 StringToBooleanConverter（Boolean.parseBoolean 语义：仅 "true" 为 true）
        return enabled.getValue();
    }

    public String username() {
        return username.getValue();
    }

    public String password() {
        return password.getValue();
    }
}
