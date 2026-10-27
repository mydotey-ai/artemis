package org.mydotey.ai.artemis.common.tls;

import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;

/**
 * gRPC TLS 装配（多服务模块共用：内部 peer、discovery/management 上游）。仅 server-auth TLS
 * （加密 + 服务端身份），不做 mTLS 客户端证书校验（v1.0 phase-2 与账户体系整合）；hostname
 * 校验按 gRPC/JDK 默认语义，证书须带 SAN 覆盖目标地址。
 *
 * <p>入站侧产出 {@link KeyManagerFactory} 而非 netty SslContext：spring-grpc 的 factory 经
 * {@code Grpc.newServerBuilderForPort(port, credentials)} 构造（ServerCredentials 冻结安全设置，
 * customizer 再调 {@code sslContext} 会抛 "Cannot change security"），TLS 由
 * {@code NettyGrpcServerFactory} 构造参数（KeyManagerFactory/TrustManagerFactory/ClientAuth）接入。
 * PEM 解析全部走 JDK 自带（CertificateFactory/KeyFactory），不引 BouncyCastle。
 */
public final class GrpcTlsContexts {

    private GrpcTlsContexts() {}

    /** 入站 server 证书 → KeyManagerFactory（spring-grpc factory 构造参数；enabled=true 时调用）。 */
    public static KeyManagerFactory serverKeyManagers(TlsServerSettings settings) {
        try {
            List<Certificate> chain = pemCertificates(settings.certChainPath().trim());
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("no CERTIFICATE PEM block found");
            }
            PrivateKey privateKey = pemPrivateKey(
                    settings.privateKeyPath().trim(),
                    chain.get(0).getPublicKey().getAlgorithm());
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("artemis-tls", privateKey, new char[0], chain.toArray(new Certificate[0]));
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(store, new char[0]);
            return keyManagers;
        } catch (Exception e) {
            // PEM 解析/密钥工厂失败（含坏 PEM 的 IllegalArgumentException）带路径归一为启动失败语义
            throw new IllegalStateException(
                    "failed to build server TLS key managers from certChain=" + settings.certChainPath()
                            + " privateKey=" + settings.privateKeyPath(),
                    e);
        }
    }

    /** 出站 client SSL 上下文：自定义信任锚（仅 trustCaPath 已配置时调用；未配置走 gRPC 默认 TLS + JVM 信任库）。 */
    public static SslContext client(TlsClientSettings settings) {
        if (settings.trustCaPathOrNull() == null) {
            throw new IllegalStateException("client TLS context requires a configured trust CA (trustCaPath is blank)");
        }
        try {
            return GrpcSslContexts.forClient()
                    .trustManager(new File(settings.trustCaPathOrNull().trim()))
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "failed to build client TLS context from trustCa=" + settings.trustCaPathOrNull(), e);
        }
    }

    /** 出站 channel（host:port 形态）：disabled 保持明文（与既有行为一致），enabled 走 TLS（自定义 CA 时挂信任锚）。 */
    public static ManagedChannelBuilder<?> channelForAddress(String host, int port, TlsClientSettings tls) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
        return tls.enabled() ? withClientTls(builder, tls) : builder.usePlaintext();
    }

    /** 出站 channel（target 字符串形态，SDK 地址列表用）：语义同 {@link #channelForAddress}。 */
    public static ManagedChannelBuilder<?> channelForTarget(String target, TlsClientSettings tls) {
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target);
        return tls.enabled() ? withClientTls(builder, tls) : builder.usePlaintext();
    }

    private static ManagedChannelBuilder<?> withClientTls(NettyChannelBuilder builder, TlsClientSettings tls) {
        // 未配 trustCa 不挂自定义上下文：gRPC 默认 TLS + JVM 信任库（公网 CA 场景）
        return tls.trustCaPathOrNull() != null ? builder.sslContext(client(tls)) : builder;
    }

    /** PEM（可能多段 CERTIFICATE）→ 证书链。 */
    private static List<Certificate> pemCertificates(String path) throws Exception {
        List<Certificate> certificates = new ArrayList<>();
        for (byte[] der : pemBlocks(Files.readString(Path.of(path), StandardCharsets.UTF_8), "CERTIFICATE")) {
            certificates.add(
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der)));
        }
        return certificates;
    }

    /** PKCS#8 PEM（BEGIN PRIVATE KEY）→ 私钥；算法由证书公钥决定（RSA/EC/Ed25519）。 */
    private static PrivateKey pemPrivateKey(String path, String algorithm) throws Exception {
        List<byte[]> blocks = pemBlocks(Files.readString(Path.of(path), StandardCharsets.UTF_8), "PRIVATE KEY");
        if (blocks.size() != 1) {
            throw new IllegalArgumentException("expect exactly one PRIVATE KEY PEM block: " + path);
        }
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(blocks.get(0)));
    }

    /** 提取 {@code -----BEGIN <type>-----} 段的 DER 字节（base64 解码，JDK 自带，不引三方）。 */
    private static List<byte[]> pemBlocks(String pem, String type) {
        List<byte[]> blocks = new ArrayList<>();
        StringBuilder base64 = new StringBuilder();
        boolean inBlock = false;
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("-----BEGIN " + type + "-----")) {
                inBlock = true;
                base64.setLength(0);
            } else if (trimmed.equals("-----END " + type + "-----")) {
                if (inBlock) {
                    blocks.add(Base64.getMimeDecoder().decode(base64.toString()));
                }
                inBlock = false;
            } else if (inBlock && !trimmed.isEmpty() && !trimmed.startsWith("-----")) {
                base64.append(trimmed);
            }
        }
        return blocks;
    }
}
