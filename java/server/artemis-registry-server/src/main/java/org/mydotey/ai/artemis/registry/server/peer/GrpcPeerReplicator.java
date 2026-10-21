package org.mydotey.ai.artemis.registry.server.peer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.application.RegistryProtoMapper;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.PeerReplicator;
import org.mydotey.ai.artemis.registry.domain.PermanentReplicationException;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.ai.artemis.registry.server.auth.PeerAuthConfig;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 出站 peer 复制 gRPC 传输（领域端口 {@link PeerReplicator} 的装配层实现）：
 * per-peer 惰性 channel（静态成员，全生命周期复用）；deadline 与认证头每次调用现取
 * （动态配置，支持运行期轮换）；ACK_FAIL / gRPC 错误抛异常，交由扇出 worker 退避重试。
 */
public final class GrpcPeerReplicator implements PeerReplicator {

    static final String KEY_SEND_TIMEOUT_MS = "artemis.registry.replication.sendTimeoutMs";

    private static final long DEFAULT_SEND_TIMEOUT_MS = 5_000;

    private final PeerAuthConfig auth;
    /** 动态配置：每次投递现取最新值。 */
    private final Property<String, Long> sendTimeoutMs;

    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public GrpcPeerReplicator(StringProperties properties, PeerAuthConfig auth) {
        this.auth = auth;
        this.sendTimeoutMs = properties.getLongProperty(
                KEY_SEND_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_SEND_TIMEOUT_MS, 100L, 600_000L));
    }

    @Override
    public void send(ClusterMember peer, ReplicationBatch batch) {
        ReplicateResponse response;
        try {
            response = stub(peer).replicate(mapper.toReplicateRequest(batch));
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNIMPLEMENTED
                    || code == Status.Code.UNAUTHENTICATED
                    || code == Status.Code.PERMISSION_DENIED) {
                // 确定性失败（滚动升级窗口 / 凭证不匹配）：重试同批永无成功，交扇出丢批
                throw new PermanentReplicationException(
                        "replicate permanently failed for peer " + peer.nodeId() + ": " + e.getStatus(), e);
            }
            throw new IllegalStateException(
                    "replicate transport failed for peer " + peer.nodeId() + ": " + e.getStatus(), e);
        }
        if (response.getStatus().getAck() != Ack.ACK_SUCCESS) {
            String detail = response.getStatus().hasError()
                    ? response.getStatus().getError().getCode() + ": "
                            + response.getStatus().getError().getMessage()
                    : "";
            // 整批拒绝走 ACK_FAIL（契约语义）：批次本身非法，重试无意义
            throw new PermanentReplicationException("replicate rejected by peer " + peer.nodeId() + ", " + detail);
        }
    }

    /** deadline 每次调用现算（固化绝对时刻不可复用）；认证头同样现取（动态配置、运行期可轮换）。 */
    private ReplicationServiceGrpc.ReplicationServiceBlockingStub stub(ClusterMember peer) {
        ReplicationServiceGrpc.ReplicationServiceBlockingStub stub = ReplicationServiceGrpc.newBlockingStub(
                        channel(peer))
                .withDeadlineAfter(sendTimeoutMs.getValue(), TimeUnit.MILLISECONDS);
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

    /** 惰性建、按 node-id 复用；domain host 不带方括号（wire 括号形式仅在成员表配置层处理）。 */
    private ManagedChannel channel(ClusterMember peer) {
        return channels.computeIfAbsent(
                peer.nodeId(),
                ignored -> ManagedChannelBuilder.forAddress(
                                peer.peer().host(), peer.peer().port())
                        .usePlaintext()
                        .build());
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }
}
