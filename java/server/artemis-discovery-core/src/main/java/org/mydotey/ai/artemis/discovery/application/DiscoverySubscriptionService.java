package org.mydotey.ai.artemis.discovery.application;

import org.mydotey.ai.artemis.discovery.domain.DiscoverySelector;
import org.mydotey.ai.artemis.discovery.domain.PushTarget;
import org.mydotey.ai.artemis.discovery.domain.Subscription;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

/**
 * 订阅用例：选择器校验（信任边界一次，语义同查询）→ 订阅登记 + 基线。
 * 校验失败抛 {@link IllegalArgumentException}，由出口转 gRPC status INVALID_ARGUMENT。
 */
public final class DiscoverySubscriptionService {

    private final SubscriptionManager manager;

    public DiscoverySubscriptionService(SubscriptionManager manager) {
        this.manager = manager;
    }

    public Subscription subscribe(DiscoverySelector selector, PushTarget target) {
        return subscribe(selector, target, null);
    }

    public Subscription subscribe(DiscoverySelector selector, PushTarget target, String subscriber) {
        selector.requireValid();
        return manager.subscribe(selector, target, subscriber);
    }
}
