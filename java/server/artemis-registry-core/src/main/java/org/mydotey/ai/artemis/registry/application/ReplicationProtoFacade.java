package org.mydotey.ai.artemis.registry.application;

import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponseData;

/**
 * 复制契约的 proto 门面（dto 进出）：委托应用服务 + 响应映射。
 * `Bootstrap` 尚未实现（phase-3），gRPC 侧自然返回 UNIMPLEMENTED。
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
                .addAllMembers(service.members().stream().map(mapper::toProto).toList())
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
}
