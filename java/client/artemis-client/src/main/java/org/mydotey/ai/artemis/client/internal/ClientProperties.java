package org.mydotey.ai.artemis.client.internal;

import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationProperties;
import org.mydotey.scf.facade.StringProperties;

/**
 * SDK string 配置项工具（服务端 artemis-common ScfProperties 同款纪律，SDK 自备不引服务端模块）。
 * 不设 static：SCF manager 按键缓存 PropertyConfig 且 static 语义固化首建值——同 JVM 多次
 * {@code ArtemisClients.newClient()}（测试逐例重建）会读到缓存旧值；动态 getValue 由消费方在
 * 构造期取值固化衍生数据，newClient 即该 client 的「启动」边界。
 * 多类共享的键（auth）经本类同一工厂方法获取，保证同 key 单一 PropertyConfig（SCF 缓存纪律）。
 */
final class ClientProperties {

    static final String KEY_AUTH_USERNAME = "artemis.client.auth.username";
    static final String KEY_AUTH_PASSWORD = "artemis.client.auth.password";

    private ClientProperties() {}

    /** 非空白判断（认证头「两端都配才发送」语义共用，单一实现防两路径漂移）。 */
    static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

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

    /** Basic 认证用户名（gRPC interceptor 与 HTTP 前置寻址共用，同 key 单一 config）。 */
    static Property<String, String> authUsername(StringProperties properties) {
        return stringProperty(
                properties, KEY_AUTH_USERNAME, null, "auth username (both set = Basic auth, absent = no header)");
    }

    /** Basic 认证密码（gRPC interceptor 与 HTTP 前置寻址共用，同 key 单一 config）。 */
    static Property<String, String> authPassword(StringProperties properties) {
        return stringProperty(
                properties, KEY_AUTH_PASSWORD, null, "auth password (both set = Basic auth, absent = no header)");
    }
}
