package org.mydotey.ai.artemis.common.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS IT 证书设施（test-jar 供各 server IT 复用）：以 JDK 自带 keytool 生成自签 PKCS12
 * （SAN = dns:localhost,ip:127.0.0.1，进程内拓扑地址可达），Java 侧从 KeyStore 导出
 * PEM 三件套——cert-chain（X.509）、private-key（PKCS#8）、trust-ca（自签证书即 CA，
 * 客户端直接信任）。零三方依赖（不引 BouncyCastle/openssl）。
 * {@link #close()} 删除临时目录（含未加密私钥——共享 CI 上不留密钥材料），IT @AfterAll 调用。
 */
public final class TestCertificates implements AutoCloseable {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final Path certChainPem;
    private final Path privateKeyPem;
    private final Path trustCaPem;

    private TestCertificates(Path certChainPem, Path privateKeyPem, Path trustCaPem) {
        this.certChainPem = certChainPem;
        this.privateKeyPem = privateKeyPem;
        this.trustCaPem = trustCaPem;
    }

    public static TestCertificates generate(String name) {
        try {
            Path dir = Files.createTempDirectory("artemis-tls-" + name + "-");
            Path keystore = dir.resolve(name + ".p12");
            runKeytool(
                    "-genkeypair",
                    "-alias",
                    name,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "3650",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1",
                    "-keystore",
                    keystore.toString(),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    new String(PASSWORD),
                    "-keypass",
                    new String(PASSWORD));

            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, PASSWORD);
            }
            Certificate certificate = store.getCertificate(name);
            PrivateKey privateKey = (PrivateKey) store.getKey(name, PASSWORD);

            Path certChainPem = dir.resolve(name + "-cert-chain.pem");
            Path privateKeyPem = dir.resolve(name + "-private-key.pem");
            Path trustCaPem = dir.resolve(name + "-trust-ca.pem");
            writePem(certChainPem, "CERTIFICATE", certificate.getEncoded());
            writePem(privateKeyPem, "PRIVATE KEY", privateKey.getEncoded());
            Files.copy(certChainPem, trustCaPem);
            return new TestCertificates(certChainPem, privateKeyPem, trustCaPem);
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate test certificates", e);
        }
    }

    public String certChainPath() {
        return certChainPem.toString();
    }

    public String privateKeyPath() {
        return privateKeyPem.toString();
    }

    /** 客户端信任锚：自签场景 = 证书本身。 */
    public String trustCaPath() {
        return trustCaPem.toString();
    }

    /** 信任本证书的 javax SSLContext（IT 以 JDK HttpClient 驱动 https 管理面/console 用）。 */
    public SSLContext clientSslContext() {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate)
                    factory.generateCertificate(new ByteArrayInputStream(Files.readAllBytes(trustCaPem)));
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("ca", certificate);
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("failed to build client SSL context", e);
        }
    }

    @Override
    public void close() {
        try (var paths = Files.walk(certChainPem.getParent())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理尽力而为：残留临时目录不影响测试结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        String keytool =
                System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "keytool";
        List<String> command = new ArrayList<>();
        command.add(keytool);
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IOException(
                    "keytool failed (exit=" + (finished ? process.exitValue() : "timeout") + "): " + output);
        }
    }

    private static void writePem(Path target, String type, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        Files.writeString(target, "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n");
    }
}
