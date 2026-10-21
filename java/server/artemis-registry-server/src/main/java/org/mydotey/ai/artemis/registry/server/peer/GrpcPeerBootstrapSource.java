package org.mydotey.ai.artemis.registry.server.peer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapRequest;
import org.mydotey.ai.artemis.proto.replication.v1.BootstrapResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.registry.application.RegistryProtoMapper;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.PeerBootstrapSource;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 出站 peer 启动数据 gRPC 实现（领域端口 {@link PeerBootstrapSource}）：
 * 状态查询与全量 bootstrap 复用 {@link PeerChannels} 的 channel / 认证；deadline 每次现取。
 * bootstrap 流正常结束 = 同步完成；流中断/异常抛给调用方整轮重来。
 */
public final class GrpcPeerBootstrapSource implements PeerBootstrapSource {

    static final String KEY_BOOTSTRAP_TIMEOUT_MS = "artemis.registry.bootstrap.timeoutMs";

    private static final long DEFAULT_BOOTSTRAP_TIMEOUT_MS = 60_000;

    private final PeerChannels channels;
    private final ClusterMembers clusterMembers;
    private final Property<String, Long> bootstrapTimeoutMs;
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public GrpcPeerBootstrapSource(StringProperties properties, PeerChannels channels, ClusterMembers clusterMembers) {
        this.channels = channels;
        this.clusterMembers = clusterMembers;
        this.bootstrapTimeoutMs = properties.getLongProperty(
                KEY_BOOTSTRAP_TIMEOUT_MS, new RangeValueConfig<>(DEFAULT_BOOTSTRAP_TIMEOUT_MS, 1_000L, 600_000L));
    }

    @Override
    public NodeStatus status(ClusterMember peer, long timeoutMs) {
        GetNodeStatusResponse response = channels.stub(peer)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getNodeStatus(GetNodeStatusRequest.getDefaultInstance());
        if (response.getStatus().getAck() != Ack.ACK_SUCCESS) {
            throw new IllegalStateException("peer " + peer.nodeId() + " returned node status failure");
        }
        return mapper.toDomain(response.getData().getSelf());
    }

    @Override
    public List<ServiceInstances> bootstrap(ClusterMember peer) {
        Iterator<BootstrapResponse> responses = channels.stub(peer)
                .withDeadlineAfter(bootstrapTimeoutMs.getValue(), TimeUnit.MILLISECONDS)
                .bootstrap(BootstrapRequest.newBuilder()
                        .setRequesterNodeId(selfNodeId())
                        .build());
        List<ServiceInstances> services = new ArrayList<>();
        while (responses.hasNext()) {
            BootstrapResponse response = responses.next();
            if (response.getStatus().getAck() != Ack.ACK_SUCCESS) {
                throw new IllegalStateException("peer " + peer.nodeId() + " returned bootstrap failure");
            }
            for (org.mydotey.ai.artemis.proto.common.v1.ServiceInstances service :
                    response.getData().getServicesList()) {
                services.add(mapper.toDomain(service));
            }
        }
        return List.copyOf(services);
    }

    private String selfNodeId() {
        return clusterMembers.self().map(ClusterMember::nodeId).orElse("");
    }
}
