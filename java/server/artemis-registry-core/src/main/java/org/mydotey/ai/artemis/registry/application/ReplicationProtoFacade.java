package org.mydotey.ai.artemis.registry.application;

import java.util.stream.Stream;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponseData;

/**
 * 复制契约的 proto 门面（dto 进出）：委托应用服务 + 响应映射。
 * 节点状态（内部/对外同构）与全量 bootstrap 快照导出（server 侧按字节预算分片）。
 */
public final class ReplicationProtoFacade {

    private final ReplicationService service;
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public ReplicationProtoFacade(ReplicationService service) {
        this.service = service;
    }

    public GetNodeStatusResponse getNodeStatus(GetNodeStatusRequest request) {
        GetNodeStatusResponseData data = GetNodeStatusResponseData.newBuilder()
                .setSelf(mapper.toProto(service.nodeStatus()))
                .addAllMembers(
                        service.clusterNodes().stream().map(mapper::toProto).toList())
                .build();
        return GetNodeStatusResponse.newBuilder()
                .setStatus(mapper.successStatus())
                .setData(data)
                .build();
    }

    /** 增量批量入站：校验失败整批 ACK_FAIL + INVALID_ARGUMENT（契约注释语义，发送侧重试）。 */
    public ReplicateResponse replicate(ReplicateRequest request) {
        return ReplicateResponse.newBuilder()
                .setStatus(mapper.toProto(service.replicate(mapper.toDomain(request.getBatch()))))
                .setData(ReplicateResponseData.getDefaultInstance())
                .build();
    }

    /**
     * 全量注册表快照的**惰性** proto 流（bootstrap 数据源，空表为空流）：
     * server 接入层逐 service 分片发送，避免一次性物化整表 proto 副本。
     */
    public Stream<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> bootstrapSnapshotStream() {
        return service.bootstrapSnapshot().stream().map(mapper::toProto);
    }
}
