package org.mydotey.ai.artemis.client.internal;

import org.mydotey.scf.ConfigurationSourceConfig;
import org.mydotey.scf.DefaultConfigurationSourceConfig;
import org.mydotey.scf.source.stringproperty.StringPropertyConfigurationSource;

/**
 * 环境变量 source：把点号/camel 配置键映射为容器友好的大写蛇形名。
 * SCF 内置 env source 按 key 精确匹配，点号键在容器/K8s 环境不可设置，SDK 缺省装配用本类替代
 * （与服务端 artemis-common 同名实现同构；client 不依赖服务端模块故自备）。
 *
 * <p>例：{@code artemis.client.serverAddresses} → {@code ARTEMIS_CLIENT_SERVER_ADDRESSES}。
 */
public final class RelaxedEnvironmentConfigurationSource
        extends StringPropertyConfigurationSource<ConfigurationSourceConfig> {

    public RelaxedEnvironmentConfigurationSource() {
        super(new DefaultConfigurationSourceConfig.Builder()
                .setName("environmentVariables")
                .build());
    }

    @Override
    public String getPropertyValue(String key) {
        return System.getenv(toEnvironmentName(key));
    }

    static String toEnvironmentName(String key) {
        StringBuilder name = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                name.append('_');
            } else if (Character.isUpperCase(c)) {
                if (i > 0 && key.charAt(i - 1) != '.' && key.charAt(i - 1) != '_') {
                    name.append('_');
                }
                name.append(c);
            } else {
                name.append(Character.toUpperCase(c));
            }
        }
        return name.toString();
    }
}
