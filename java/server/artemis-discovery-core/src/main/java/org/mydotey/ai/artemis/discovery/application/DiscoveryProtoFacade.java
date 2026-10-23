package org.mydotey.ai.artemis.discovery.application;

import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.QueryRequest;
import org.mydotey.ai.artemis.discovery.domain.Subscription;

/**
 * 查询/订阅契约的 proto 门面：dto → 领域 → dto 一站转换；查询校验失败转契约错误码
 * INVALID_ARGUMENT，订阅校验失败抛 {@link IllegalArgumentException} 由流出口转 gRPC
 * status（流未开，无 ResponseStatus 可用）。
 */
public final class DiscoveryProtoFacade {

    private final DiscoveryQueryService queryService;
    private final DiscoverySubscriptionService subscriptionService;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public DiscoveryProtoFacade(DiscoveryQueryService queryService, DiscoverySubscriptionService subscriptionService) {
        this.queryService = queryService;
        this.subscriptionService = subscriptionService;
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

    /** 订阅：登记 + 基线推送（经 target）；选择器校验失败向上抛，由出口转 status。 */
    public Subscription subscribe(
            org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest request, PushTarget target) {
        return subscriptionService.subscribe(mapper.toSelector(request), target);
    }
}
