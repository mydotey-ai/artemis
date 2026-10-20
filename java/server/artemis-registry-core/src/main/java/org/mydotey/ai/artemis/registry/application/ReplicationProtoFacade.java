package org.mydotey.ai.artemis.registry.application;

import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponseData;

/**
 * 复制契约的 proto 门面（dto 进出）：委托应用服务 + 响应映射。
 * `Replicate` / `Bootstrap` 尚未实现（phase-2/3），gRPC 侧自然返回 UNIMPLEMENTED。
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
}
