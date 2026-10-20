package org.mydotey.ai.artemis.registry.server.peer;

import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 内部 peer 端口配置（静态：端口绑定启动后不可变）。内部端口承载 peer 复制/成员状态
 * 与 management 只读查询，与外部 SDK 端口分离。
 *
 * <p>地址**无默认值**：未配置时绑定所有网卡（内部端口必须可被 peer 跨节点访问，集群形态的
 * 基础）；需要限定网卡时显式配置 `artemis.registry.internal.address`。内部认证默认关闭，
 * 部署必须用网络策略隔离内部端口，或显式启用内部认证（启动时 WARN 提示）。
 * 端口必须是固定端口（1..65535，不支持 0 动态端口）；非法值按 SCF 既有语义回落默认值。
 */
public final class PeerServerConfig {

    static final String KEY_PORT = "artemis.registry.internal.port";
    static final String KEY_ADDRESS = "artemis.registry.internal.address";
    static final String KEY_MAX_INBOUND_MESSAGE_BYTES = "artemis.registry.internal.maxInboundMessageBytes";
    static final String KEY_SHUTDOWN_GRACE_MS = "artemis.registry.internal.shutdownGraceMs";

    private static final int DEFAULT_PORT = 9093;
    /** 地址无默认值：空 = 绑定所有网卡。 */
    private static final String DEFAULT_ADDRESS = "";

    private static final String WILDCARD_ADDRESS = "0.0.0.0";
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final long DEFAULT_SHUTDOWN_GRACE_MS = 5_000;

    private final Property<String, Integer> port;
    private final Property<String, String> address;
    private final Property<String, Integer> maxInboundMessageBytes;
    private final Property<String, Long> shutdownGraceMs;

    public PeerServerConfig(StringProperties properties) {
        this.port = ScfProperties.staticInt(
                properties, KEY_PORT, DEFAULT_PORT, 1, 65535, "internal peer gRPC fixed port (1..65535)");
        this.address = ScfProperties.staticString(
                properties,
                KEY_ADDRESS,
                DEFAULT_ADDRESS,
                "internal peer gRPC bind address (static; empty = all interfaces)");
        this.maxInboundMessageBytes = ScfProperties.staticInt(
                properties,
                KEY_MAX_INBOUND_MESSAGE_BYTES,
                DEFAULT_MAX_INBOUND_MESSAGE_BYTES,
                1024,
                Integer.MAX_VALUE,
                "internal peer gRPC max inbound message bytes (static)");
        this.shutdownGraceMs = ScfProperties.staticLong(
                properties, KEY_SHUTDOWN_GRACE_MS, DEFAULT_SHUTDOWN_GRACE_MS, 0, 300_000, "peer shutdown grace ms");
    }

    public int port() {
        return port.getValue();
    }

    public String address() {
        return address.getValue();
    }

    /** gRPC server 绑定的 host:port；未配置地址时绑所有网卡，裸 IPv6 自动补方括号。 */
    public String bindAddress() {
        String host = address();
        if (host.isBlank()) {
            host = WILDCARD_ADDRESS;
        } else if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host + ":" + port();
    }

    public int maxInboundMessageBytes() {
        return maxInboundMessageBytes.getValue();
    }

    public long shutdownGraceMs() {
        return shutdownGraceMs.getValue();
    }
}
