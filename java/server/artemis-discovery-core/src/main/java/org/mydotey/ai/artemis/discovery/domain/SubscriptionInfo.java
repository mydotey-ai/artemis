package org.mydotey.ai.artemis.discovery.domain;

import java.time.Instant;

/**
 * 活跃订阅快照条目（管理面调用方视角，D-A2/D-A3）：进程内单调 id + 选择器 + 调用方标识
 * （gRPC 远端地址）+ 登记时刻。不可变。
 */
public record SubscriptionInfo(long id, DiscoverySelector selector, String subscriber, Instant establishedAt) {}
