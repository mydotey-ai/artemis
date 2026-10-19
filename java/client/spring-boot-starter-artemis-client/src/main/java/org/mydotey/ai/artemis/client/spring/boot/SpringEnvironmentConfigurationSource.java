package org.mydotey.ai.artemis.client.spring.boot;

import org.mydotey.scf.AbstractConfigurationSource;
import org.mydotey.scf.ConfigurationSourceConfig;
import org.mydotey.scf.DefaultConfigurationSourceConfig;
import org.springframework.core.env.Environment;

/**
 * Spring {@link Environment} source：SCF 看到 Spring Boot 标准覆盖路径（命令行、system properties、
 * 环境变量、application.properties/YAML）中的 {@code artemis.client.*} 键。
 *
 * <p>{@code Environment.getProperty} 只做精确名查找（relaxed binding 属 Binder 行为），因此对
 * camelCase 键补三种常见写法：SDK 自有的大写蛇形（{@code ARTEMIS_CLIENT_SERVER_ADDRESSES}）、
 * kebab-case（YAML 惯例，{@code artemis.client.server-addresses}）与小写蛇形（properties 惯例）；
 * Spring 的 {@code SystemEnvironmentPropertySource} 已覆盖 {@code ARTEMIS_CLIENT_SERVERADDRESSES}。
 */
final class SpringEnvironmentConfigurationSource extends AbstractConfigurationSource<ConfigurationSourceConfig> {

    private final Environment environment;

    SpringEnvironmentConfigurationSource(Environment environment) {
        super(new DefaultConfigurationSourceConfig.Builder()
                .setName("springEnvironment")
                .build());
        this.environment = environment;
    }

    @Override
    protected Object getPropertyValue(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        String name = (String) key;
        Object value = environment.getProperty(name);
        if (value != null) {
            return value;
        }
        for (String variant : new String[] {toUpperSnake(name), toDelimited(name, '-'), toDelimited(name, '_')}) {
            value = environment.getProperty(variant);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** camelCase + 点号 → 大写蛇形环境变量名（点号也是分隔符）。 */
    private static String toUpperSnake(String key) {
        StringBuilder name = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                name.append('_');
            } else if (Character.isUpperCase(c)) {
                if (i > 0 && key.charAt(i - 1) != '.' && key.charAt(i - 1) != '_' && key.charAt(i - 1) != '-') {
                    name.append('_');
                }
                name.append(c);
            } else {
                name.append(Character.toUpperCase(c));
            }
        }
        return name.toString();
    }

    /** camelCase → 小写蛇形或 kebab-case（点号保留为配置层级分隔）。 */
    private static String toDelimited(String key, char delimiter) {
        StringBuilder name = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && key.charAt(i - 1) != '.' && key.charAt(i - 1) != '_' && key.charAt(i - 1) != '-') {
                    name.append(delimiter);
                }
                name.append(Character.toLowerCase(c));
            } else {
                name.append(c);
            }
        }
        return name.toString();
    }
}
