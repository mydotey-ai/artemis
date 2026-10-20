package org.mydotey.ai.artemis.management.server;

import java.util.Map;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/**
 * 测试用 SCF properties 设施：内存 source，不依赖系统属性/环境变量/文件。
 * {@link #mutable(Map)} 允许运行期改值（验证动态配置语义）。
 */
public final class TestProperties {

    private final MemoryMapConfigurationSource source;
    private final StringProperties properties;

    private TestProperties(Map<String, String> values) {
        this.source = SimpleConfigurationSources.newMemoryMapSource();
        this.source.setProperties(values);
        ConfigurationManager manager = ConfigurationManagers.newManager("test", Map.of(1, source));
        this.properties = new StringProperties(manager);
    }

    public static TestProperties mutable(Map<String, String> values) {
        return new TestProperties(values);
    }

    public static StringProperties of(Map<String, String> values) {
        return new TestProperties(values).properties;
    }

    public static StringProperties empty() {
        return of(Map.of());
    }

    public StringProperties properties() {
        return properties;
    }

    public void set(String key, String value) {
        source.setPropertyValue(key, value);
    }
}
