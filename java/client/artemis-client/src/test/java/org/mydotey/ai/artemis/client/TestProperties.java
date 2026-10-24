package org.mydotey.ai.artemis.client;

import java.util.LinkedHashMap;
import java.util.Map;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;

/** 测试 SCF properties：系统属性源（逐例新建 manager，避免 SCF 按键缓存跨例污染）。 */
public final class TestProperties {

    private TestProperties() {}

    public static StringProperties systemProperties() {
        Map<Integer, ConfigurationSource> sources = new LinkedHashMap<>();
        sources.put(3, SimpleConfigurationSources.newSystemPropertiesSource());
        return new StringProperties(ConfigurationManagers.newManager("test-discovery-" + System.nanoTime(), sources));
    }
}
