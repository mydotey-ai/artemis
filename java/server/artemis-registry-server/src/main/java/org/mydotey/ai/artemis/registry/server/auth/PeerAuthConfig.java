package org.mydotey.ai.artemis.registry.server.auth;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 内部 peer 端口认证配置：**默认关闭，且不提供默认凭证**；显式启用时 username/password
 * 必填（启动时空白即失败）。启用后与外部 {@code artemis.registry.auth.*} 互不通用（认证隔离）。
 * 启用开关与凭证均为动态配置：运行期可轮换，无需重启。
 */
@Slf4j
public final class PeerAuthConfig {

    static final String KEY_ENABLED = "artemis.registry.internal.auth.enabled";
    static final String KEY_USERNAME = "artemis.registry.internal.auth.username";
    static final String KEY_PASSWORD = "artemis.registry.internal.auth.password";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public PeerAuthConfig(StringProperties properties) {
        // 动态配置：启用开关与凭证运行期可改（认证轮换无需重启）
        this.enabled = properties.getBooleanProperty(KEY_ENABLED, false);
        this.username = properties.getStringProperty(KEY_USERNAME, "");
        this.password = properties.getStringProperty(KEY_PASSWORD, "");
        if (enabled() && (username().isBlank() || password().isBlank())) {
            throw new IllegalArgumentException(
                    KEY_USERNAME + " and " + KEY_PASSWORD + " are required when " + KEY_ENABLED + "=true");
        }
        if (!enabled()) {
            log.warn(
                    "internal peer authentication is disabled ({}), ensure the internal port is network-isolated",
                    KEY_ENABLED);
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
