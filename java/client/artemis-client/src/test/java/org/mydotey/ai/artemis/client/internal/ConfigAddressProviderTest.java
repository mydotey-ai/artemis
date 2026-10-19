package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** 配置驱动选址：select 重读配置（动态），解析校验 fail-fast。 */
class ConfigAddressProviderTest {

    private static final String KEY = ConfigAddressProvider.KEY_SERVER_ADDRESSES;

    @Test
    void should_returnSingleAddress_when_listHasOne() {
        ConfigAddressProvider provider = provider("localhost:9090");
        for (int i = 0; i < 10; i++) {
            assertThat(provider.select()).isEqualTo("localhost:9090");
        }
    }

    @Test
    void should_pickWithinList_when_multipleAddresses() {
        ConfigAddressProvider provider = provider("a:9090,b:9090,c:9090");
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(provider.select());
        }
        assertThat(picked).containsExactlyInAnyOrder("a:9090", "b:9090", "c:9090");
    }

    @Test
    void should_rereadConfig_when_propertyChanged() {
        // 动态重读：运行时改地址列表，下次 select 生效（重连换址的配置基础）
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setPropertyValue(KEY, "first:9090");
        ConfigAddressProvider provider = new ConfigAddressProvider(ConfigAddressProvider.property(new StringProperties(
                ConfigurationManagers.newManager("test-address-mutable", Collections.singletonMap(1, source)))));
        assertThat(provider.select()).isEqualTo("first:9090");
        source.setPropertyValue(KEY, "second:9090");
        assertThat(provider.select()).isEqualTo("second:9090");
    }

    @Test
    void should_rejectInvalid_when_missingOrMalformed() {
        assertThatThrownBy(() -> provider(null).select()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider("").select()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider("no-port").select()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider(" ,, ").select()).isInstanceOf(IllegalArgumentException.class);
    }

    private static ConfigAddressProvider provider(String addresses) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        if (addresses != null) {
            source.setPropertyValue(KEY, addresses);
        }
        return new ConfigAddressProvider(
                ConfigAddressProvider.property(new StringProperties(ConfigurationManagers.newManager(
                        "test-address-" + System.nanoTime(), Collections.singletonMap(1, source)))));
    }
}
