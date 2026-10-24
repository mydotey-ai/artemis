package org.mydotey.ai.artemis.dist;

import lombok.Builder;
import lombok.Value;

/**
 * all-in-one 组合端口（不可变）。生产入口经 {@link AllInOneConfig}（SCF {@code artemis.dist.*}，
 * 范围 1..65535、非法 fail-fast）；组合启动 {@code ArtemisAllInOneServer.start(options)} 亦接受
 * 显式构造（测试/嵌入用，{@code 0} 表示随机端口——仅 HTTP/gRPC/管理端口，peer 端口产品语义
 * 不接受 0，须预分配固定值）。
 */
@Value
@Builder
public class AllInOneOptions {

    public static final int DEFAULT_REGISTRY_GRPC_PORT = 9090;
    public static final int DEFAULT_REGISTRY_HTTP_PORT = 8080;
    public static final int DEFAULT_REGISTRY_MANAGEMENT_PORT = 9091;
    public static final int DEFAULT_REGISTRY_PEER_PORT = 9093;
    public static final int DEFAULT_DISCOVERY_GRPC_PORT = 9095;
    public static final int DEFAULT_DISCOVERY_HTTP_PORT = 8090;
    public static final int DEFAULT_DISCOVERY_MANAGEMENT_PORT = 9096;

    @Builder.Default
    int registryGrpcPort = DEFAULT_REGISTRY_GRPC_PORT;

    @Builder.Default
    int registryHttpPort = DEFAULT_REGISTRY_HTTP_PORT;

    @Builder.Default
    int registryManagementPort = DEFAULT_REGISTRY_MANAGEMENT_PORT;

    @Builder.Default
    int registryPeerPort = DEFAULT_REGISTRY_PEER_PORT;

    @Builder.Default
    int discoveryGrpcPort = DEFAULT_DISCOVERY_GRPC_PORT;

    @Builder.Default
    int discoveryHttpPort = DEFAULT_DISCOVERY_HTTP_PORT;

    @Builder.Default
    int discoveryManagementPort = DEFAULT_DISCOVERY_MANAGEMENT_PORT;

    /**
     * 宿主已配置的 discovery 节点目录（{@code artemis.discovery.nodes} 原始值；null/blank = 未配置，
     * launcher 注入 loopback 默认）。由 {@link AllInOneConfig} 随端口一次性读出，避免组合期重复
     * 建 SCF manager；测试/嵌入直构 options 时留空即取默认行为。
     */
    String configuredDiscoveryNodes;
}
