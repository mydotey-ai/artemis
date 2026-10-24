package org.mydotey.ai.artemis.management.server.grpc;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 内部 gRPC 端口认证配置（动态：调用时现取）——独立于外部 HTTP 账户（模式同 registry
 * 内部 peer 端口）：默认关闭，启用须显式配置凭证；关闭认证的部署以网络策略隔离。
 */
public final class ManagementGrpcAuthConfig {

    static final String KEY_ENABLED = "artemis.management.grpc.auth.enabled";
    static final String KEY_USERNAME = "artemis.management.grpc.auth.username";
    static final String KEY_PASSWORD = "artemis.management.grpc.auth.password";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public ManagementGrpcAuthConfig(StringProperties properties) {
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
