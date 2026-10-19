package org.mydotey.ai.artemis.client.internal;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationProperties;
import org.mydotey.scf.facade.StringProperties;

/**
 * SDK string 配置项工具（服务端 artemis-common ScfProperties 同款纪律，SDK 自备不引服务端模块）。
 * 不设 static：SCF manager 按键缓存 PropertyConfig 且 static 语义固化首建值——同 JVM 多次
 * {@code ArtemisClients.newClient()}（测试逐例重建）会读到缓存旧值；动态 getValue 由消费方在
 * 构造期取值固化衍生数据（地址列表/认证头），newClient 即该 client 的「启动」边界。
 */
final class ClientProperties {

    private ClientProperties() {}

    /** string Property；defaultValue 可为 null（必配项由消费方校验）。 */
    static Property<String, String> stringProperty(
            StringProperties properties, String key, String defaultValue, String doc) {
        return properties
                .getManager()
                .getProperty(ConfigurationProperties.<String, String>newConfigBuilder()
                        .setKey(key)
                        .setValueType(String.class)
                        .setDefaultValue(defaultValue)
                        .setDoc(doc)
                        .build());
    }
}
