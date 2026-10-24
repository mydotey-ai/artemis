package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.TestProperties;

/** clientId 配置：默认 default、安全文件名字符集（快照路径隔离）。 */
class ClientIdTest {

    private static final String KEY = ClientProperties.KEY_CLIENT_ID;

    @AfterEach
    void tearDown() {
        System.clearProperty(KEY);
    }

    @Test
    void should_default_and_validate_client_id() {
        assertThat(ClientProperties.clientId(TestProperties.systemProperties())).isEqualTo("default");

        System.setProperty(KEY, " client-a ");
        assertThat(ClientProperties.clientId(TestProperties.systemProperties())).isEqualTo("client-a");

        System.setProperty(KEY, "a/b");
        assertThatThrownBy(() -> ClientProperties.clientId(TestProperties.systemProperties()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KEY);

        System.setProperty(KEY, "..");
        assertThatThrownBy(() -> ClientProperties.clientId(TestProperties.systemProperties()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KEY);

        // SCF 将空白值视为未设置 → 回落默认值（不构成路径穿越风险）
        System.setProperty(KEY, " ");
        assertThat(ClientProperties.clientId(TestProperties.systemProperties())).isEqualTo("default");
    }
}
