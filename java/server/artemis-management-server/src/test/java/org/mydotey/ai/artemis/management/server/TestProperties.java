package org.mydotey.ai.artemis.management.server;

import java.util.Map;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/**
 * 测试用 SCF properties 设施：内存 source，不依赖系统属性/环境变量/文件。
 */
public final class TestProperties {

    private TestProperties() {}

    public static StringProperties of(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        ConfigurationManager manager = ConfigurationManagers.newManager("test", Map.of(1, source));
        return new StringProperties(manager);
    }

    public static StringProperties empty() {
        return of(Map.of());
    }
}
