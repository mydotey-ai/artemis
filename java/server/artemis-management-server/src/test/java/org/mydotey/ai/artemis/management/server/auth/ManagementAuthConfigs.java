package org.mydotey.ai.artemis.management.server.auth;

import java.util.Map;
import org.mydotey.ai.artemis.management.server.TestProperties;

/**
 * 测试用认证配置构造（跨包可见的配置键封装）。
 */
public final class ManagementAuthConfigs {

    private ManagementAuthConfigs() {}

    public static ManagementAuthConfig enabled(String username, String password) {
        return new ManagementAuthConfig(TestProperties.of(Map.of(
                ManagementAuthConfig.KEY_ENABLED,
                "true",
                ManagementAuthConfig.KEY_USERNAME,
                username,
                ManagementAuthConfig.KEY_PASSWORD,
                password)));
    }

    public static ManagementAuthConfig disabled() {
        return new ManagementAuthConfig(TestProperties.of(Map.of(ManagementAuthConfig.KEY_ENABLED, "false")));
    }
}
