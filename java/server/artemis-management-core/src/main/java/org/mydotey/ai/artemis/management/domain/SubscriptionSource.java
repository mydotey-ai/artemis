package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 订阅关系组合的逐源结果（v0.5 phase-5）：discovery 节点互为独立投影副本，
 * 逐源并列不跨源合并去重（源列即排障维度）。error 非 null = 该源不可达/失败，
 * subscriptions 恒为空（per-source fail-open，只读排障面不因单源故障整体失败）。
 */
public record SubscriptionSource(String endpoint, String error, List<Subscription> subscriptions) {

    public SubscriptionSource {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }
}
