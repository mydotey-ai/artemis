package org.mydotey.ai.artemis.registry.server.peer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.ConcurrentHashMap;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.server.auth.PeerAuthConfig;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * per-peer gRPC channel 池（复制投递与 bootstrap 探测/拉取共用）：惰性建、按 node-id 复用、
 * 全生命周期持有；认证头每次调用现取（动态配置、运行期可轮换）。
 *
 * <p>bootstrap 分片可能接近 16 MiB，channel 显式提升接收上限。
 */
public final class PeerChannels implements AutoCloseable {

    static final String KEY_MAX_INBOUND_MESSAGE_BYTES = "artemis.registry.bootstrap.maxInboundMessageBytes";

    private static final int DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final PeerAuthConfig auth;
    private final int maxInboundMessageBytes;
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    PeerChannels(StringProperties properties, PeerAuthConfig auth) {
        this.auth = auth;
        Property<String, Integer> maxInbound = ScfProperties.staticInt(
                properties,
                KEY_MAX_INBOUND_MESSAGE_BYTES,
                DEFAULT_MAX_INBOUND_MESSAGE_BYTES,
                1024 * 1024,
                64 * 1024 * 1024,
                "max inbound message bytes for peer bootstrap channel (static)");
        this.maxInboundMessageBytes = maxInbound.getValue();
    }

    /** 指向 peer 内部端点的阻塞 stub（无 deadline，由调用方按用例附加）。 */
    ReplicationServiceGrpc.ReplicationServiceBlockingStub stub(ClusterMember peer) {
        ReplicationServiceGrpc.ReplicationServiceBlockingStub stub =
                ReplicationServiceGrpc.newBlockingStub(channel(peer));
        if (auth.enabled()) {
            // headerValueOrNull：凭证不完整（username/password 任一为空）视为未配置，不附残缺头
            String headerValue = BasicAuth.headerValueOrNull(auth.username(), auth.password());
            if (headerValue != null) {
                Metadata headers = new Metadata();
                headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), headerValue);
                stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            }
        }
        return stub;
    }

    /** domain host 不带方括号（wire 括号形式仅在成员表配置层处理）。 */
    private ManagedChannel channel(ClusterMember peer) {
        return channels.computeIfAbsent(
                peer.nodeId(),
                ignored -> ManagedChannelBuilder.forAddress(
                                peer.peer().host(), peer.peer().port())
                        .usePlaintext()
                        .maxInboundMessageSize(maxInboundMessageBytes)
                        .build());
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }
}
