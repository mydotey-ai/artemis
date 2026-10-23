package org.mydotey.ai.artemis.discovery;

import java.util.Map;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 测试用 SCF properties：内存 source，不依赖系统属性/环境变量/文件（模块内自备，同构 registry 测试设施）。 */
public final class TestProperties {

    private final MemoryMapConfigurationSource source;
    private final StringProperties properties;

    public TestProperties(Map<String, String> initialValues) {
        this.source = SimpleConfigurationSources.newMemoryMapSource();
        this.source.setProperties(initialValues);
        ConfigurationManager manager = ConfigurationManagers.newManager("test-discovery", Map.of(1, source));
        this.properties = new StringProperties(manager);
    }

    public StringProperties properties() {
        return properties;
    }
}
