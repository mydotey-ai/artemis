package org.mydotey.ai.artemis.registry.server.peer;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.registry.application.RegistryProtoMapper;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.PeerReplicator;
import org.mydotey.ai.artemis.registry.domain.PermanentReplicationException;
import org.mydotey.ai.artemis.registry.domain.ReplicationBatch;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 出站 peer 复制 gRPC 传输（领域端口 {@link PeerReplicator} 的装配层实现）：
 * 复用 {@link PeerChannels} 的 per-peer 惰性 channel；deadline 每次调用现取（动态配置）。
 * ACK_FAIL / gRPC 错误抛异常，交由扇出 worker 退避重试。
 */
public final class GrpcPeerReplicator implements PeerReplicator {

    static final String KEY_SEND_TIMEOUT_MS = "artemis.registry.replication.sendTimeoutMs";

    private static final long DEFAULT_SEND_TIMEOUT_MS = 5_000;

    /** 动态配置：每次投递现取最新值。 */
    private final Property<String, Long> sendTimeoutMs;

    private final PeerChannels channels;
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public GrpcPeerReplicator(StringProperties properties, PeerChannels channels) {
        this.channels = channels;
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

    /** deadline 每次调用现算（固化绝对时刻不可复用）；认证头由 PeerChannels 现取。 */
    private ReplicationServiceGrpc.ReplicationServiceBlockingStub stub(ClusterMember peer) {
        return channels.stub(peer).withDeadlineAfter(sendTimeoutMs.getValue(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        // channel 生命周期归 PeerChannels 池；此处无需释放
    }
}
