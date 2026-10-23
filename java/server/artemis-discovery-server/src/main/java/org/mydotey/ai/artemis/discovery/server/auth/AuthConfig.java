package org.mydotey.ai.artemis.discovery.server.auth;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 外部端口单账户认证配置（D-D10，模式同 registry 外部端口；差异：discovery 默认关闭——
 * 发现面不持有可写事实，启用与否交由部署侧网络策略与凭证需求决定）。
 * 认证项在本类内定义一次（Property 字段），gRPC 拦截器共享该配置对象。
 */
@Slf4j
public final class AuthConfig {

    static final String KEY_ENABLED = "artemis.discovery.auth.enabled";
    static final String KEY_USERNAME = "artemis.discovery.auth.username";
    static final String KEY_PASSWORD = "artemis.discovery.auth.password";

    private final Property<String, Boolean> enabled;
    private final Property<String, String> username;
    private final Property<String, String> password;

    public AuthConfig(StringProperties properties) {
        // 动态配置：启用开关与凭证运行期可改（认证轮换无需重启）
        this.enabled = properties.getBooleanProperty(KEY_ENABLED, false);
        this.username = properties.getStringProperty(KEY_USERNAME, "");
        this.password = properties.getStringProperty(KEY_PASSWORD, "");
        if (enabled()) {
            log.info("discovery authentication enabled ({}=true)", KEY_ENABLED);
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
