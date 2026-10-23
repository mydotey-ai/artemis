package org.mydotey.ai.artemis.discovery.server.upstream;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 上游认证配置（动态：调用时现取，运行期可轮换）——registry 内部 peer 端口的 BasicAuth
 * 客户端侧凭证，键型同 registry internal auth。
 */
public final class UpstreamAuthConfig {

    static final String KEY_ENABLED = "artemis.discovery.upstream.auth.enabled";
    static final String KEY_USERNAME = "artemis.discovery.upstream.auth.username";
    static final String KEY_PASSWORD = "artemis.discovery.upstream.auth.password";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public UpstreamAuthConfig(StringProperties properties) {
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
