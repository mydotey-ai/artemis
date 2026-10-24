package org.mydotey.ai.artemis.discovery.server.upstream;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * management 规则流认证配置（动态：调用时现取）——management 内部 gRPC 端口的
 * BasicAuth 客户端侧凭证，键型同 management internal auth。
 */
public final class ManagementAuthConfig {

    static final String KEY_ENABLED = "artemis.discovery.management.auth.enabled";
    static final String KEY_USERNAME = "artemis.discovery.management.auth.username";
    static final String KEY_PASSWORD = "artemis.discovery.management.auth.password";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public ManagementAuthConfig(StringProperties properties) {
        this.enabled = properties.getBooleanProperty(KEY_ENABLED, false);
        this.username = properties.getStringProperty(KEY_USERNAME, "");
        this.password = properties.getStringProperty(KEY_PASSWORD, "");
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
