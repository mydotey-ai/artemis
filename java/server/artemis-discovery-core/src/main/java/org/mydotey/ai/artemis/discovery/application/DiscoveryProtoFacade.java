package org.mydotey.ai.artemis.discovery.application;

import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.QueryRequest;
import org.mydotey.ai.artemis.discovery.domain.Subscription;

/**
 * 查询/订阅/对账契约的 proto 门面：dto → 领域 → dto 一站转换；查询与对账校验失败转契约
 * 错误码 INVALID_ARGUMENT，订阅校验失败抛 {@link IllegalArgumentException} 由流出口转 gRPC
 * status（流未开，无 ResponseStatus 可用）。
 */
public final class DiscoveryProtoFacade {

    private final DiscoveryQueryService queryService;
    private final DiscoverySubscriptionService subscriptionService;
    private final DiscoveryReconciliationService reconciliationService;
    private final DiscoveryProtoMapper mapper = DiscoveryProtoMapper.INSTANCE;

    public DiscoveryProtoFacade(
            DiscoveryQueryService queryService,
            DiscoverySubscriptionService subscriptionService,
            DiscoveryReconciliationService reconciliationService) {
        this.queryService = queryService;
        this.subscriptionService = subscriptionService;
        this.reconciliationService = reconciliationService;
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

    /** hash 对账（D-F1/D-F3）：checksum 表 + 新鲜度快照；校验失败转 INVALID_ARGUMENT。 */
    public org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse checksum(
            org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest request) {
        try {
            return mapper.toSuccessProto(reconciliationService.checksum(mapper.toSelector(request)));
        } catch (IllegalArgumentException e) {
            return mapper.toErrorChecksumProto(ErrorCodes.INVALID_ARGUMENT, e.getMessage());
        }
    }

    /** 订阅：登记 + 基线推送（经 target）；选择器校验失败向上抛，由出口转 status。 */
    public Subscription subscribe(
            org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest request, PushTarget target) {
        return subscriptionService.subscribe(mapper.toSelector(request), target);
    }
}
