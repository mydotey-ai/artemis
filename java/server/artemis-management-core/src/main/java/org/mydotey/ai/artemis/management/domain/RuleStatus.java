package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;

/**
 * 规则生效水位视图（状态 API 出口）：摘除规则流（phase-5）+ 路由规则流（phase-2）
 * 各自的当前版本与各活订阅方已应用版本（哪个版本、多少节点已生效）。currentVersion
 * 由流侧派生（摘除 = 翻转记录 sequence、路由规则 = dispatcher tick），appliedVersion
 * 为订阅方自报（ack），两者对齐即该节点已生效。
 */
public record RuleStatus(
        long currentVersion,
        List<SubscriberWatermark> subscribers,
        int appliedLatestCount,
        RouteRulesStatus routeRules) {

    /** 单订阅方水位：appliedVersion null = 已订阅尚未 ack；atLatest = 已应用当前版本。 */
    public record SubscriberWatermark(String subscriberId, Long appliedVersion, Instant lastAckAt, boolean atLatest) {}

    /** 路由规则流水位节（phase-2，与外层同构；独立版本域）。 */
    public record RouteRulesStatus(long currentVersion, List<SubscriberWatermark> subscribers, int appliedLatestCount) {

        public RouteRulesStatus {
            subscribers = List.copyOf(subscribers);
        }
    }

    public RuleStatus {
        subscribers = List.copyOf(subscribers);
    }
}
