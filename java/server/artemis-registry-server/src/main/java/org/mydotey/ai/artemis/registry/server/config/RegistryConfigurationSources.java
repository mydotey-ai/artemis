package org.mydotey.ai.artemis.registry.server.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mydotey.ai.artemis.common.config.RelaxedEnvironmentConfigurationSource;
import org.mydotey.ai.artemis.common.config.ReloadablePropertiesFileConfigurationSource;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.springframework.core.env.Environment;

/**
 * registry 配置 source 装配（自定义业务配置；框架/三方组件配置仍归 Spring Boot）。
 * 优先级数字大者优先（SCF 降序取第一个非空值）：系统属性 3 &gt; 环境变量 2 &gt; 工作目录配置文件 1。
 */
public final class RegistryConfigurationSources {

    static final String CONFIG_FILE_NAME = "artemis.properties";
    static final String MANAGER_NAME = "artemis-registry";

    private RegistryConfigurationSources() {}

    public static ConfigurationManager manager(Environment environment) {
        Map<Integer, ConfigurationSource> sources = new LinkedHashMap<>();
        Path configFile = Path.of(CONFIG_FILE_NAME).toAbsolutePath();
        if (Files.exists(configFile)) {
            sources.put(1, new ReloadablePropertiesFileConfigurationSource(configFile));
        }
        sources.put(2, new RelaxedEnvironmentConfigurationSource());
        sources.put(3, SimpleConfigurationSources.newSystemPropertiesSource());
        sources.put(4, new SpringEnvironmentConfigurationSource(environment));
        return ConfigurationManagers.newManager(MANAGER_NAME, sources);
    }
}
