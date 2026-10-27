package org.mydotey.ai.artemis.common.tls;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** SSL 上下文与 channel builder 装配：合法 PEM 构建/明文-TLS 分支；坏 PEM 带路径报错。 */
class GrpcTlsContextsTest {

    @Test
    void should_build_server_key_managers_from_generated_certificates() {
        TestCertificates certificates = TestCertificates.generate("ctx-server");

        TlsServerSettings settings = TlsSettings.server(
                properties(Map.of(
                        "test.tls.enabled", "true",
                        "test.tls.certChainPath", certificates.certChainPath(),
                        "test.tls.privateKeyPath", certificates.privateKeyPath())),
                "test.tls");

        assertThatCode(() -> GrpcTlsContexts.serverKeyManagers(settings)).doesNotThrowAnyException();
    }

    @Test
    void should_build_client_context_from_trust_ca() {
        TestCertificates certificates = TestCertificates.generate("ctx-client");

        TlsClientSettings settings = TlsSettings.client(
                properties(Map.of("test.tls.enabled", "true", "test.tls.trustCaPath", certificates.trustCaPath())),
                "test.tls");

        assertThatCode(() -> GrpcTlsContexts.client(settings)).doesNotThrowAnyException();
    }

    @Test
    void should_fail_with_path_context_when_pem_is_garbage() throws Exception {
        TestCertificates certificates = TestCertificates.generate("ctx-garbage");

        TlsServerSettings settings = TlsSettings.server(
                properties(Map.of(
                        "test.tls.enabled", "true",
                        "test.tls.certChainPath", certificates.certChainPath(),
                        "test.tls.privateKeyPath", writeGarbagePem())),
                "test.tls");

        assertThatThrownBy(() -> GrpcTlsContexts.serverKeyManagers(settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("certChain");
    }

    @Test
    void should_build_plaintext_and_tls_channel_builders_per_settings() throws Exception {
        TestCertificates certificates = TestCertificates.generate("ctx-channel");
        TlsClientSettings disabled = TlsSettings.client(properties(Map.of()), "test.tls");
        TlsClientSettings defaultTrust = TlsSettings.client(properties(Map.of("test.tls.enabled", "true")), "test.tls");
        TlsClientSettings customCa = TlsSettings.client(
                properties(Map.of("test.tls.enabled", "true", "test.tls.trustCaPath", certificates.trustCaPath())),
                "test.tls");

        assertThatCode(() -> GrpcTlsContexts.channelForAddress("127.0.0.1", 9090, disabled))
                .doesNotThrowAnyException();
        assertThatCode(() -> GrpcTlsContexts.channelForTarget("127.0.0.1:9090", defaultTrust))
                .doesNotThrowAnyException();
        assertThatCode(() -> GrpcTlsContexts.channelForTarget("127.0.0.1:9090", customCa))
                .doesNotThrowAnyException();
    }

    private static String writeGarbagePem() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("garbage", ".pem");
        java.nio.file.Files.writeString(
                file, "-----BEGIN PRIVATE KEY-----\nnot-base64!!!\n-----END PRIVATE KEY-----\n");
        return file.toString();
    }

    private static StringProperties properties(Map<String, String> values) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(values);
        ConfigurationManager manager = ConfigurationManagers.newManager("grpc-tls-test", Map.of(1, source));
        return new StringProperties(manager);
    }
}
