package org.mydotey.ai.artemis.registry.server.config;

import org.mydotey.scf.AbstractConfigurationSource;
import org.mydotey.scf.ConfigurationSourceConfig;
import org.mydotey.scf.DefaultConfigurationSourceConfig;
import org.springframework.core.env.Environment;

/**
 * Spring {@link Environment} source：让 SCF 看到 Spring Boot 标准覆盖路径（命令行 `--key=value`、
 * application.properties/imported 文件）中的业务配置键；优先级最高（命令行应覆盖其它来源）。
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
        return key instanceof String ? environment.getProperty((String) key) : null;
    }
}
