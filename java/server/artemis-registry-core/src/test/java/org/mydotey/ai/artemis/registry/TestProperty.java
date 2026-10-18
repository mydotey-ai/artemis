package org.mydotey.ai.artemis.registry;

import java.util.function.Consumer;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.Property;
import org.mydotey.scf.PropertyChangeEvent;
import org.mydotey.scf.PropertyConfig;

/** 测试用固定 {@link Property}（用于短周期等绕过生产值域的测试构造）。 */
public final class TestProperty<T> implements Property<String, T> {

    private final T value;

    private TestProperty(T value) {
        this.value = value;
    }

    public static <T> TestProperty<T> fixed(T value) {
        return new TestProperty<>(value);
    }

    @Override
    public PropertyConfig<String, T> getConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public T getValue() {
        return value;
    }

    @Override
    public ConfigurationSource getSource() {
        return null;
    }

    @Override
    public void addChangeListener(Consumer<PropertyChangeEvent<String, T>> listener) {
        // no-op
    }
}
