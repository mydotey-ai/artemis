package org.mydotey.ai.artemis.common.config;

import java.util.function.Function;
import org.mydotey.scf.Property;
import org.mydotey.scf.PropertyConfig;
import org.mydotey.scf.facade.ConfigurationProperties;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.mydotey.scf.type.TypeConverter;
import org.mydotey.scf.type.string.StringToBooleanConverter;
import org.mydotey.scf.type.string.StringToIntConverter;
import org.mydotey.scf.type.string.StringToLongConverter;

/**
 * 静态配置项通用工具（guidelines §10）：由**使用方**传入自己的键/默认值/值域/转换器，返回
 * {@link Property} 字段（{@code setStatic(true)}，启动后值固化），使用处自行 {@code getValue()}。
 * 本类不持有任何配置项定义。
 *
 * <p>支持任意目标类型：{@link #staticProperty} 为通用入口，long/int/string/boolean 为常用便捷方法。
 */
public final class ScfProperties {

    private ScfProperties() {}

    /** 通用静态 Property 创建：converter/valueFilter 可为 null（String 等源类型直通）。 */
    public static <T> Property<String, T> staticProperty(
            StringProperties properties,
            String key,
            Class<T> valueType,
            T defaultValue,
            TypeConverter<String, T> converter,
            Function<T, T> valueFilter,
            String doc) {
        PropertyConfig.Builder<String, T> builder = ConfigurationProperties.<String, T>newConfigBuilder()
                .setKey(key)
                .setValueType(valueType)
                .setDefaultValue(defaultValue)
                .setStatic(true)
                .setDoc(doc);
        if (converter != null) {
            builder.addValueConverter(converter);
        }
        if (valueFilter != null) {
            builder.setValueFilter(valueFilter);
        }
        return properties.getManager().getProperty(builder.build());
    }

    public static Property<String, Long> staticLong(
            StringProperties properties, String key, long defaultValue, long lowerBound, long upperBound, String doc) {
        return staticProperty(
                properties,
                key,
                Long.class,
                defaultValue,
                StringToLongConverter.DEFAULT,
                new RangeValueConfig<>(defaultValue, lowerBound, upperBound).toRangeValueFilter(),
                doc);
    }

    public static Property<String, Integer> staticInt(
            StringProperties properties, String key, int defaultValue, int lowerBound, int upperBound, String doc) {
        return staticProperty(
                properties,
                key,
                Integer.class,
                defaultValue,
                StringToIntConverter.DEFAULT,
                new RangeValueConfig<>(defaultValue, lowerBound, upperBound).toRangeValueFilter(),
                doc);
    }

    public static Property<String, String> staticString(
            StringProperties properties, String key, String defaultValue, String doc) {
        return staticProperty(properties, key, String.class, defaultValue, null, null, doc);
    }

    public static Property<String, Boolean> staticBoolean(
            StringProperties properties, String key, boolean defaultValue, String doc) {
        return staticProperty(
                properties, key, Boolean.class, defaultValue, StringToBooleanConverter.DEFAULT, null, doc);
    }
}
