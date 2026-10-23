package org.mydotey.ai.artemis.discovery.application;

import org.mydotey.ai.artemis.discovery.domain.QueryRequest;

/**
 * 查询契约的 proto 门面：dto → 领域 → dto 一站转换；校验失败转契约错误码
 * INVALID_ARGUMENT（整体性失败走 gRPC status，由 readiness 拦截器承担）。
 */
public final class DiscoveryProtoFacade {

    private final DiscoveryQueryService queryService;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public DiscoveryProtoFacade(DiscoveryQueryService queryService) {
        this.queryService = queryService;
    }

    public org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse query(
            org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest request) {
        try {
            QueryRequest domain = mapper.toDomain(request);
            return mapper.toSuccessProto(queryService.query(domain));
        } catch (IllegalArgumentException e) {
            return mapper.toErrorProto(ErrorCodes.INVALID_ARGUMENT, e.getMessage());
        }
    }
}
