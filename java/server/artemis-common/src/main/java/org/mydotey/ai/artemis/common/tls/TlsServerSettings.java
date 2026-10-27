package org.mydotey.ai.artemis.common.tls;

/**
 * 服务端 TLS 静态配置值对象（guidelines §10.2 静态分型：证书路径启动后不可变，轮换 = 换文件重启）。
 * 经 {@link TlsSettings#server} 绑定（enabled=true 时启动即 fail-fast 校验路径可读）。
 */
public final class TlsServerSettings {

    private final boolean enabled;
    private final String certChainPath;
    private final String privateKeyPath;

    TlsServerSettings(boolean enabled, String certChainPath, String privateKeyPath) {
        this.enabled = enabled;
        this.certChainPath = certChainPath;
        this.privateKeyPath = privateKeyPath;
    }

    public boolean enabled() {
        return enabled;
    }

    /** X.509 证书链 PEM 路径（enabled=true 时非空且可读）。 */
    public String certChainPath() {
        return certChainPath;
    }

    /** PKCS#8 私钥 PEM 路径（enabled=true 时非空且可读）。 */
    public String privateKeyPath() {
        return privateKeyPath;
    }
}
