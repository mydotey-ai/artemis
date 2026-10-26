package org.mydotey.ai.artemis.discovery.server.upstream;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.mydotey.ai.artemis.common.auth.BasicAuth;

/**
 * 管理规则流 gRPC 公共设施（phase-3 #10 收口）：channel 池（per endpoint 惰性建、
 * 全生命周期复用）+ 认证头 + 端点轮换 + 订阅方标识 + ack 端点记忆。两规则流 source
 * （ejection / route-rule）各持一份实例——独立池、独立轮换、独立订阅条目（水位互不混流）。
 * 投影 / 逻辑实例 source 形态异构（per-cluster 源、bootstrap 协议），不共用。
 */
final class ManagementRuleChannels implements AutoCloseable {

    private final ManagementConfig config;
    private final ManagementAuthConfig auth;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final ConcurrentHashMap<UpstreamConfig.Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final String subscriber = newSubscriberId(); // DNS 查找一次（重连轮次复用）
    private volatile UpstreamConfig.Endpoint watchEndpoint; // ack 必须送达持有订阅的端点

    ManagementRuleChannels(ManagementConfig config, ManagementAuthConfig auth) {
        this.config = config;
        this.auth = auth;
    }

    String subscriberId() {
        return subscriber;
    }

    /** 轮换选定下一个端点并记忆为当前 watch 端点。 */
    UpstreamConfig.Endpoint nextWatchEndpoint() {
        List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
        UpstreamConfig.Endpoint endpoint =
                endpoints.get(Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size()));
        watchEndpoint = endpoint;
        return endpoint;
    }

    /** ack 端点：优先当前 watch 所在端点（订阅登记在该 management 进程）；无活 watch（防御路径）轮换兜底。 */
    UpstreamConfig.Endpoint ackEndpoint() {
        UpstreamConfig.Endpoint endpoint = watchEndpoint;
        if (endpoint != null) {
            return endpoint;
        }
        List<UpstreamConfig.Endpoint> endpoints = config.endpoints();
        return endpoints.get(Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size()));
    }

    /** 认证头按调用现取（动态配置、运行期可轮换）；凭证不完整视为未配置，不附残缺头。 */
    io.grpc.Channel channel(UpstreamConfig.Endpoint endpoint) {
        ManagedChannel channel = channels.computeIfAbsent(
                endpoint,
                ignored -> ManagedChannelBuilder.forAddress(endpoint.host(), endpoint.port())
                        .usePlaintext()
                        .maxInboundMessageSize(config.maxInboundMessageBytes())
                        // 规则流是被动长流：keepalive ping 检测半开连接（参数同投影上游）
                        .keepAliveTime(310, TimeUnit.SECONDS)
                        .keepAliveTimeout(20, TimeUnit.SECONDS)
                        .build());
        if (!auth.enabled()) {
            return channel;
        }
        String headerValue = BasicAuth.headerValueOrNull(auth.username(), auth.password());
        if (headerValue == null) {
            return channel;
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), headerValue);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }

    /** 订阅方标识：host + 进程级短随机后缀——同 host 多节点部署水位条目不互撞。 */
    private static String newSubscriberId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown";
        }
        return "artemis-discovery@" + host + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
