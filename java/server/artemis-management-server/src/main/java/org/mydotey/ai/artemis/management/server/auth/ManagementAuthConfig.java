package org.mydotey.ai.artemis.management.server.auth;

import lombok.extern.slf4j.Slf4j;
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
        // 动态配置：启用开关与凭证运行期可改（认证轮换无需重启）
        this.enabled = properties.getBooleanProperty(KEY_ENABLED, true);
        this.username = properties.getStringProperty(KEY_USERNAME, DEFAULT_USERNAME);
        this.password = properties.getStringProperty(KEY_PASSWORD, DEFAULT_PASSWORD);
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
