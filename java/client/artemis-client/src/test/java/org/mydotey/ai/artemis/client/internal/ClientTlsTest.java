package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** SDK TLS 静态配置：默认明文（eval 零依赖不变）、enabled 绑定、坏 trustCa 创建即 fail-fast。 */
class ClientTlsTest {

    @TempDir
    Path tempDir;

    @Test
    void should_default_to_plaintext_when_no_tls_keys_set() {
        ClientTls tls = ClientTls.from(properties(new java.util.HashMap<>()));

        assertThat(tls.enabled()).isFalse();
    }

    @Test
    void should_bind_enabled_with_blank_trust_ca_as_default_trust() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("artemis.client.tls.enabled", "true");
        values.put("artemis.client.tls.trustCaPath", "  ");

        ClientTls tls = ClientTls.from(properties(values));

        assertThat(tls.enabled()).isTrue();
    }

    @Test
    void should_fail_fast_when_trust_ca_unreadable() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("artemis.client.tls.enabled", "true");
        values.put(
                "artemis.client.tls.trustCaPath",
                tempDir.resolve("missing-ca.pem").toString());

        assertThatThrownBy(() -> ClientTls.from(properties(values)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artemis.client.tls.trustCaPath");
    }

    @Test
    void should_not_validate_trust_ca_when_disabled() throws Exception {
        Map<String, String> values = new java.util.HashMap<>();
        values.put(
                "artemis.client.tls.trustCaPath",
                tempDir.resolve("missing-ca.pem").toString());
        Path pem = tempDir.resolve("any.pem");
        Files.write(pem, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ClientTls tls = ClientTls.from(properties(values));

        assertThat(tls.enabled()).isFalse();
    }

    @Test
    void should_bind_enabled_from_system_properties_source() throws Exception {
        // 复现 IT 失败形态：ArtemisClients 缺省源（relaxed env + 系统属性）下 enabled 误判为 false。
        // trustCa 用真实证书 PEM（从 JDK cacerts 导出首张）：ClientTls.from 现在创建即解析（fail-fast）
        Path pem = tempDir.resolve("ca.pem");
        Files.write(pem, firstJdkCaCertificatePem());
        System.setProperty("artemis.client.tls.enabled", "true");
        System.setProperty("artemis.client.tls.trustCaPath", pem.toString());
        try {
            java.util.LinkedHashMap<Integer, org.mydotey.scf.ConfigurationSource> sources =
                    new java.util.LinkedHashMap<>();
            sources.put(2, new RelaxedEnvironmentConfigurationSource());
            sources.put(3, org.mydotey.scf.facade.SimpleConfigurationSources.newSystemPropertiesSource());
            StringProperties defaults = new StringProperties(org.mydotey.scf.facade.ConfigurationManagers.newManager(
                    "client-tls-sysprobe-" + System.nanoTime(), sources));
            ClientTls tls = ClientTls.from(defaults);
            assertThat(tls.enabled())
                    .as("system properties source should enable TLS like memory source does")
                    .isTrue();
        } finally {
            System.clearProperty("artemis.client.tls.enabled");
            System.clearProperty("artemis.client.tls.trustCaPath");
        }
    }

    /** JDK cacerts 首张证书导出为 PEM（ClientTls 解析需要合法证书，不引 BouncyCastle）。 */
    private static byte[] firstJdkCaCertificatePem() throws Exception {
        String javaHome = System.getProperty("java.home");
        java.security.KeyStore store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        try (java.io.InputStream in = new java.io.FileInputStream(new java.io.File(javaHome, "lib/security/cacerts"))) {
            store.load(in, "changeit".toCharArray());
        }
        java.security.cert.X509Certificate certificate = (java.security.cert.X509Certificate)
                store.getCertificate(store.aliases().nextElement());
        String base64 =
                java.util.Base64.getMimeEncoder(64, "\n".getBytes("US-ASCII")).encodeToString(certificate.getEncoded());
        return ("-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static StringProperties properties(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        Map<Integer, ConfigurationSource> sources = new LinkedHashMap<>();
        sources.put(1, source);
        return new StringProperties(ConfigurationManagers.newManager("client-tls-test-" + System.nanoTime(), sources));
    }
}
