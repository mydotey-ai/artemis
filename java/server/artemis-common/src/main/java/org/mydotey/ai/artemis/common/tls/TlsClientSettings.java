package org.mydotey.ai.artemis.common.tls;

/**
 * 客户端 TLS 静态配置值对象（guidelines §10.2 静态分型）：enabled=true 时出站连接走 TLS；
 * trustCaPath 为空表示用 JVM 默认信任库（公网 CA 场景），非空时以该 CA 为唯一信任锚（私有 CA）。
 * 经 {@link TlsSettings#client} 绑定（trustCaPath 配置但不可读时启动即 fail-fast）。
 */
public final class TlsClientSettings {

    private final boolean enabled;
    private final String trustCaPath;

    TlsClientSettings(boolean enabled, String trustCaPath) {
        this.enabled = enabled;
        this.trustCaPath = trustCaPath;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 自定义信任锚 PEM 路径；null = 未配置（JVM 默认信任库）。 */
    public String trustCaPathOrNull() {
        return trustCaPath;
    }
}
