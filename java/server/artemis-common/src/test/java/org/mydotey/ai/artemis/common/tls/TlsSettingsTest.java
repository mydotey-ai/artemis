package org.mydotey.ai.artemis.common.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** TLS 配置绑定 fail-fast 语义（guidelines §10.3）：启动必需项缺失/不可读拒绝启动并报键名；disabled 零校验。 */
class TlsSettingsTest {

    private static final String PREFIX = "artemis.test.tls";

    @TempDir
    Path tempDir;

    @Test
    void should_default_to_plaintext_when_no_keys_set() {
        StringProperties properties = properties(Map.of());

        TlsServerSettings server = TlsSettings.server(properties, PREFIX);
        TlsClientSettings client = TlsSettings.client(properties, PREFIX);

        assertThat(server.enabled()).isFalse();
        assertThat(client.enabled()).isFalse();
        assertThat(client.trustCaPathOrNull()).isNull();
    }

    @Test
    void should_fail_fast_when_tls_server_enabled_but_paths_missing() throws Exception {
        String cert = writeFile("cert.pem");
        StringProperties properties = properties(Map.of(PREFIX + ".enabled", "true", PREFIX + ".certChainPath", cert));

        assertThatThrownBy(() -> TlsSettings.server(properties, PREFIX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + ".privateKeyPath");
    }

    @Test
    void should_fail_fast_when_tls_cert_files_unreadable() throws Exception {
        StringProperties properties = properties(Map.of(
                PREFIX + ".enabled",
                "true",
                PREFIX + ".certChainPath",
                tempDir.resolve("missing-cert.pem").toString(),
                PREFIX + ".privateKeyPath",
                tempDir.resolve("missing-key.pem").toString()));

        assertThatThrownBy(() -> TlsSettings.server(properties, PREFIX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + ".certChainPath")
                .hasMessageContaining("readable");
    }

    @Test
    void should_fail_fast_when_trust_ca_configured_but_unreadable() {
        StringProperties properties = properties(Map.of(
                PREFIX + ".enabled",
                "true",
                PREFIX + ".trustCaPath",
                tempDir.resolve("missing-ca.pem").toString()));

        assertThatThrownBy(() -> TlsSettings.client(properties, PREFIX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + ".trustCaPath");
    }

    @Test
    void should_bind_server_settings_from_readable_pem_paths() throws Exception {
        String cert = writeFile("cert.pem");
        String key = writeFile("key.pem");
        StringProperties properties = properties(
                Map.of(PREFIX + ".enabled", "true", PREFIX + ".certChainPath", cert, PREFIX + ".privateKeyPath", key));

        TlsServerSettings server = TlsSettings.server(properties, PREFIX);

        assertThat(server.enabled()).isTrue();
        assertThat(server.certChainPath()).isEqualTo(cert);
        assertThat(server.privateKeyPath()).isEqualTo(key);
    }

    @Test
    void should_treat_blank_trust_ca_as_default_trust_store() throws Exception {
        StringProperties properties = properties(Map.of(PREFIX + ".enabled", "true", PREFIX + ".trustCaPath", "  "));

        TlsClientSettings client = TlsSettings.client(properties, PREFIX);

        assertThat(client.enabled()).isTrue();
        assertThat(client.trustCaPathOrNull()).isNull();
    }

    @Test
    void should_not_validate_paths_when_tls_disabled() {
        StringProperties properties = properties(Map.of(
                PREFIX + ".enabled",
                "false",
                PREFIX + ".certChainPath",
                tempDir.resolve("missing.pem").toString()));

        TlsServerSettings server = TlsSettings.server(properties, PREFIX);

        assertThat(server.enabled()).isFalse();
    }

    private String writeFile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "dummy-pem-content");
        return file.toString();
    }

    private static StringProperties properties(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        ConfigurationManager manager = ConfigurationManagers.newManager("tls-test", Map.of(1, source));
        return new StringProperties(manager);
    }
}
