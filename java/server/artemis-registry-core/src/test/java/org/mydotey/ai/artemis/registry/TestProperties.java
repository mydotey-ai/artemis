package org.mydotey.ai.artemis.registry;

import java.util.Map;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/**
 * 测试用 SCF properties 设施：内存 source 构造 manager + {@link StringProperties}，
 * 不依赖系统属性/环境变量/文件；支持运行期改值验证动态语义。
 */
public final class TestProperties {

    private final MemoryMapConfigurationSource source;
    private final StringProperties properties;

    public TestProperties(Map<String, String> initialValues) {
        this.source = SimpleConfigurationSources.newMemoryMapSource();
        this.source.setProperties(initialValues);
        ConfigurationManager manager = ConfigurationManagers.newManager("test", Map.of(1, source));
        this.properties = new StringProperties(manager);
    }

    public static TestProperties of(Map<String, String> initialValues) {
        return new TestProperties(initialValues);
    }

    public static StringProperties empty() {
        return new TestProperties(Map.of()).properties;
    }

    public StringProperties properties() {
        return properties;
    }

    public void set(String key, String value) {
        source.setPropertyValue(key, value);
    }
}
