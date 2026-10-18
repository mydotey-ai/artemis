package org.mydotey.ai.artemis.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

class ScfPropertiesTest {

    @Test
    void should_return_property_and_keep_initial_value_when_static_property_changed() {
        MemoryMapConfigurationSource source = memorySource(Map.of("test.static", "1000"));
        StringProperties properties = properties(source);

        Property<String, Long> property = ScfProperties.staticLong(properties, "test.static", 3000, 100, 60_000, "doc");
        source.setPropertyValue("test.static", "5000");

        assertThat(property.getValue()).isEqualTo(1000);
    }

    @Test
    void should_fall_back_to_default_when_value_out_of_range() {
        StringProperties properties = properties(memorySource(Map.of("test.static", "1")));

        Property<String, Long> property = ScfProperties.staticLong(properties, "test.static", 3000, 100, 60_000, "doc");

        assertThat(property.getValue()).isEqualTo(3000);
    }

    @Test
    void should_use_default_when_no_source_value() {
        StringProperties properties = properties(memorySource(Map.of()));

        Property<String, String> property = ScfProperties.staticString(properties, "test.string", "fallback", "doc");

        assertThat(property.getValue()).isEqualTo("fallback");
    }

    @Test
    void should_create_boolean_property_with_builtin_converter() {
        StringProperties enabled = properties(memorySource(Map.of("test.bool", "true")));
        StringProperties disabled = properties(memorySource(Map.of("test.bool", "FALSE")));

        assertThat(ScfProperties.staticBoolean(enabled, "test.bool", true, "doc")
                        .getValue())
                .isTrue();
        assertThat(ScfProperties.staticBoolean(disabled, "test.bool", true, "doc")
                        .getValue())
                .isFalse();
    }

    @Test
    void should_create_custom_typed_property_via_generic_factory() {
        StringProperties properties = properties(memorySource(Map.of("test.enum", "UP")));

        Property<String, String> property = ScfProperties.staticProperty(
                properties,
                "test.enum",
                String.class,
                "UNKNOWN",
                null,
                value -> "UP".equals(value) ? value : null,
                "doc");

        assertThat(property.getValue()).isEqualTo("UP");
    }

    private static MemoryMapConfigurationSource memorySource(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        return source;
    }

    private static StringProperties properties(MemoryMapConfigurationSource source) {
        ConfigurationManager manager = ConfigurationManagers.newManager("test", Map.of(1, source));
        return new StringProperties(manager);
    }
}
