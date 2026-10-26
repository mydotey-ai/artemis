package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;
import java.util.List;

/**
 * 规则生效水位视图（phase-5，状态 API 出口）：当前规则集版本 + 各活订阅方已应用
 * 版本（哪个版本、多少节点已生效）。currentVersion 由记录重放派生，appliedVersion
 * 为订阅方自报（ack），两者对齐即该节点已生效。
 */
public record RuleStatus(long currentVersion, List<SubscriberWatermark> subscribers, int appliedLatestCount) {

    /** 单订阅方水位：appliedVersion null = 已订阅尚未 ack；atLatest = 已应用当前版本。 */
    public record SubscriberWatermark(String subscriberId, Long appliedVersion, Instant lastAckAt, boolean atLatest) {}

    public RuleStatus {
        subscribers = List.copyOf(subscribers);
    }
}
