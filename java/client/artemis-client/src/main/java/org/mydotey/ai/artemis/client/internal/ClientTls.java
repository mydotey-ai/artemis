package org.mydotey.ai.artemis.client.internal;

import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.mydotey.scf.facade.ConfigurationProperties;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.type.string.StringToBooleanConverter;

/**
 * SDK TLS 静态配置（{@code artemis.client.tls.*}，registry/discovery gRPC 通道与 HTTP 前置寻址共用一组）：
 * enabled=true 时出站走 TLS；trustCaPath 配置时以该 CA 为唯一信任锚（gRPC 与 https 同锚），
 * 未配置用 JVM 默认信任库（公网 CA）。构造期固化（newClient 即启动边界，ClientProperties 纪律）；
 * enabled 且 trustCaPath 不可读或非合法 PEM 时创建即 fail-fast（主动解析，防坏文件进运行期重试）。
 * 多证书 CA bundle 支持（轮换 old+new CA / intermediate+root：全部载入信任 KeyStore，gRPC 与 https 同锚）。
 * 证书轮换 = 换文件重建 client（静态语义）。
 */
final class ClientTls {

    static final String KEY_ENABLED = "artemis.client.tls.enabled";
    static final String KEY_TRUST_CA_PATH = "artemis.client.tls.trustCaPath";

    private static final ClientTls PLAINTEXT = new ClientTls(false, null, null, null);

    private final boolean enabled;
    private final String trustCaPath;
    private final SslContext grpcSslContext;
    private final SSLSocketFactory httpsSocketFactory;

    private ClientTls(
            boolean enabled, String trustCaPath, SslContext grpcSslContext, SSLSocketFactory httpsSocketFactory) {
        this.enabled = enabled;
        this.trustCaPath = trustCaPath;
        this.grpcSslContext = grpcSslContext;
        this.httpsSocketFactory = httpsSocketFactory;
    }

    static ClientTls from(StringProperties properties) {
        boolean enabled = properties
                .getManager()
                .getProperty(ConfigurationProperties.<String, Boolean>newConfigBuilder()
                        .setKey(KEY_ENABLED)
                        .setValueType(Boolean.class)
                        .setDefaultValue(Boolean.FALSE)
                        .addValueConverter(StringToBooleanConverter.DEFAULT)
                        .setDoc("client TLS enabled (static; registry/discovery channels + https node fetch)")
                        .build())
                .getValue();
        String trustCaPath = properties
                .getManager()
                .getProperty(ConfigurationProperties.<String, String>newConfigBuilder()
                        .setKey(KEY_TRUST_CA_PATH)
                        .setValueType(String.class)
                        .setDefaultValue("")
                        .setDoc("trust CA PEM path (blank = JVM default trust store)")
                        .build())
                .getValue();
        String trimmed = trustCaPath == null ? "" : trustCaPath.trim();
        if (!enabled) {
            return PLAINTEXT;
        }
        if (trimmed.isEmpty()) {
            return new ClientTls(true, null, null, null);
        }
        if (!Files.isRegularFile(Paths.get(trimmed)) || !Files.isReadable(Paths.get(trimmed))) {
            throw new IllegalStateException(KEY_TRUST_CA_PATH + " is not a readable file: '" + trustCaPath + "'");
        }
        // 创建即解析（fail-fast）+ 构建期固化工件：grpc 上下文与 https 工厂一次构建复用
        SslContext grpcContext;
        try {
            grpcContext =
                    GrpcSslContexts.forClient().trustManager(new File(trimmed)).build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build client TLS context from trustCa=" + trimmed, e);
        }
        return new ClientTls(true, trimmed, grpcContext, buildHttpsSocketFactory(trimmed));
    }

    boolean enabled() {
        return enabled;
    }

    /** gRPC 出站 channel：disabled 保持明文（既有行为不变）；enabled 走 TLS（自定义 CA 时挂信任锚）。 */
    ManagedChannelBuilder<?> newChannelBuilder(String target) {
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target);
        if (!enabled) {
            return builder.usePlaintext();
        }
        return trustCaPath != null ? builder.sslContext(grpcSslContext) : builder;
    }

    /** https 前置寻址挂自定义信任锚（JDK 自带 HttpsURLConnection；未配 CA 走 JVM 默认信任库）。 */
    static void apply(HttpsURLConnection connection, ClientTls tls) {
        if (tls.enabled() && tls.trustCaPath != null) {
            connection.setSSLSocketFactory(tls.httpsSocketFactory);
        }
    }

    /** https SSLSocketFactory：PEM 全部 CERTIFICATE 块（bundle）→ 信任 KeyStore → TrustManagerFactory。 */
    private static SSLSocketFactory buildHttpsSocketFactory(String trustCaPath) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            List<Certificate> certificates = pemCertificates(trustCaPath);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("no CERTIFICATE PEM block found: " + trustCaPath);
            }
            for (int i = 0; i < certificates.size(); i++) {
                store.setCertificateEntry("ca-" + i, certificates.get(i));
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build https socket factory from trustCa=" + trustCaPath, e);
        }
    }

    /** PEM（可多段 CERTIFICATE = CA bundle）→ 证书列表；JDK 自带解析，不引三方依赖。 */
    private static List<Certificate> pemCertificates(String path) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> certificates = new ArrayList<>();
        StringBuilder base64 = new StringBuilder();
        boolean inBlock = false;
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("-----BEGIN CERTIFICATE-----")) {
                inBlock = true;
                base64.setLength(0);
            } else if (trimmed.equals("-----END CERTIFICATE-----")) {
                if (inBlock) {
                    certificates.add(factory.generateCertificate(
                            new ByteArrayInputStream(Base64.getMimeDecoder().decode(base64.toString()))));
                }
                inBlock = false;
            } else if (inBlock && !trimmed.isEmpty() && !trimmed.startsWith("-----")) {
                base64.append(trimmed);
            }
        }
        return certificates;
    }
}
