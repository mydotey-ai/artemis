package org.mydotey.ai.artemis.discovery.server.config;

import org.mydotey.scf.AbstractConfigurationSource;
import org.mydotey.scf.ConfigurationSourceConfig;
import org.mydotey.scf.DefaultConfigurationSourceConfig;
import org.springframework.core.env.Environment;

/**
 * Spring {@link Environment} source：让 SCF 看到 Spring Boot 标准覆盖路径（命令行 `--key=value`、
 * application.properties/imported 文件）中的业务配置键；优先级最高（命令行应覆盖其它来源）。
 * D-D9：与 registry-server/management-server 同构复制（common 禁 Spring 源码）。
 * 已是第三处——上移需新建共享 server-support 模块并重构两个已交付模块装配，
 * defer 至 v0.4 批次统一处理（见 phase-2 设计文档「实现结果」）。
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
