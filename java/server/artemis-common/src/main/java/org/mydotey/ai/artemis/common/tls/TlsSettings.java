package org.mydotey.ai.artemis.common.tls;

import java.nio.file.Files;
import java.nio.file.Path;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.facade.StringProperties;

/**
 * TLS 配置绑定（guidelines §10）：键前缀由使用方传入（§10.1 键在使用类定义），后缀约定本类统一——
 * {@code <prefix>.enabled}（boolean，默认 false，默认明文）／服务端 {@code .certChainPath} +
 * {@code .privateKeyPath}／客户端 {@code .trustCaPath}（可空 = JVM 默认信任库）。
 * 静态分型：证书为启动必需项，enabled=true 而路径缺失/不可读/内容非合法 PEM 时 fail-fast
 * （拒绝启动并报键名——信任锚在绑定时主动解析一次，防坏文件绕过启动校验、进入运行期重试循环）；
 * disabled 时不校验路径项（eval 明文路径零依赖不变）。
 */
public final class TlsSettings {

    static final String SUFFIX_ENABLED = "enabled";
    static final String SUFFIX_CERT_CHAIN_PATH = "certChainPath";
    static final String SUFFIX_PRIVATE_KEY_PATH = "privateKeyPath";
    static final String SUFFIX_TRUST_CA_PATH = "trustCaPath";

    // doc 按后缀全局统一：同一键组可同时绑定 server/client 两侧（peer 对称场景），
    // SCF 同 key 单 PropertyConfig 校验要求两侧 config 完全一致（§10.1）
    private static final String DOC_ENABLED = "TLS enabled (static; plaintext by default)";
    private static final String DOC_CERT_CHAIN = "X.509 cert chain PEM path (server side)";
    private static final String DOC_PRIVATE_KEY = "PKCS#8 private key PEM path (server side)";
    private static final String DOC_TRUST_CA = "trust CA PEM path (client side; blank = JVM default trust store)";

    private TlsSettings() {}

    /** 绑定服务端证书配置（入站 TLS）。 */
    public static TlsServerSettings server(StringProperties properties, String keyPrefix) {
        boolean enabled = ScfProperties.staticBoolean(properties, key(keyPrefix, SUFFIX_ENABLED), false, DOC_ENABLED)
                .getValue();
        String certChainPath = ScfProperties.staticString(
                        properties, key(keyPrefix, SUFFIX_CERT_CHAIN_PATH), "", DOC_CERT_CHAIN)
                .getValue();
        String privateKeyPath = ScfProperties.staticString(
                        properties, key(keyPrefix, SUFFIX_PRIVATE_KEY_PATH), "", DOC_PRIVATE_KEY)
                .getValue();
        if (enabled) {
            requireReadable(key(keyPrefix, SUFFIX_CERT_CHAIN_PATH), certChainPath);
            requireReadable(key(keyPrefix, SUFFIX_PRIVATE_KEY_PATH), privateKeyPath);
        }
        return new TlsServerSettings(enabled, certChainPath, privateKeyPath);
    }

    /** 绑定客户端信任配置（出站 TLS）：trustCaPath 空白视为未配置（JVM 默认信任库）。 */
    public static TlsClientSettings client(StringProperties properties, String keyPrefix) {
        boolean enabled = ScfProperties.staticBoolean(properties, key(keyPrefix, SUFFIX_ENABLED), false, DOC_ENABLED)
                .getValue();
        String trustCaPath = ScfProperties.staticString(
                        properties, key(keyPrefix, SUFFIX_TRUST_CA_PATH), "", DOC_TRUST_CA)
                .getValue();
        String trimmed = trustCaPath == null ? "" : trustCaPath.trim();
        if (enabled && !trimmed.isEmpty()) {
            requireReadable(key(keyPrefix, SUFFIX_TRUST_CA_PATH), trimmed);
            // 主动解析（结果丢弃）：PEM 非法时启动即 fail-fast，而非首次建 channel 时运行期失败
            GrpcTlsContexts.client(new TlsClientSettings(true, trimmed));
        }
        return new TlsClientSettings(enabled, trimmed.isEmpty() ? null : trimmed);
    }

    private static String key(String prefix, String suffix) {
        return prefix + "." + suffix;
    }

    /** 启动必需项校验（§10.3 fail-fast）：报配置键 + 路径，不静默回落。 */
    private static void requireReadable(String key, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalStateException(key + " must be set when TLS is enabled");
        }
        boolean readable;
        try {
            readable = Files.isRegularFile(Path.of(path.trim())) && Files.isReadable(Path.of(path.trim()));
        } catch (java.nio.file.InvalidPathException e) {
            readable = false;
        }
        if (!readable) {
            throw new IllegalStateException(key + " is not a readable file: '" + path + "'");
        }
    }
}
