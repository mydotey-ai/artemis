package org.mydotey.ai.artemis.registry.server.auth;

import java.util.HashMap;
import java.util.Map;
import org.mydotey.ai.artemis.registry.TestProperties;

/** 测试用 AuthConfig 构造（经 SCF properties 路径，覆盖生产定义方式）。 */
public final class AuthConfigs {

    private AuthConfigs() {}

    public static AuthConfig enabled(String username, String password) {
        Map<String, String> values = new HashMap<>();
        values.put(AuthConfig.KEY_ENABLED, "true");
        values.put(AuthConfig.KEY_USERNAME, username);
        values.put(AuthConfig.KEY_PASSWORD, password);
        return new AuthConfig(TestProperties.of(values).properties());
    }

    public static AuthConfig disabled() {
        return new AuthConfig(
                TestProperties.of(Map.of(AuthConfig.KEY_ENABLED, "false")).properties());
    }
}
