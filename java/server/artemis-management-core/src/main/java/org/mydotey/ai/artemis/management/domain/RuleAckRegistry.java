package org.mydotey.ai.artemis.management.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则流生效 ack 登记（ejection / route-rule 各一实例；phase-5 引入，phase-2 泛化。
 * 内存观测态，不持久化——重启经订阅方重连快照 +
 * 重新 ack 重建）。**水位只反映活订阅**：随订阅登记/断流清除条目——断连节点生效态
 * 未知不冒充已生效，同时免无界滞留；未登记订阅方的 ack 忽略。
 *
 * <p>代际令牌：register 返回的 State 是条目代际凭证，evict 只清除仍是当前代际的条目
 * ——同 id 重连（新流先登记、旧流取消/close 迟到）不会误删新条目。已知限界：旧流在途
 * 的 ack 无代际凭证，可能落入新条目短暂失真观测面（首次真实 ack 后自愈，不做协议扩展）。
 */
public final class RuleAckRegistry {

    /** 单订阅方水位：appliedVersion null = 已订阅尚未 ack（快照在途）。 */
    public record AckEntry(String subscriberId, Long appliedVersion, Instant lastAckAt) {}

    /** 订阅代际（登记凭证）：evict 持有比对，防止旧流迟到注销误删新条目。 */
    public static final class Subscription {}

    private static final class State {
        final Subscription subscription = new Subscription();
        long appliedVersion = -1;
        Instant lastAckAt;
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, State> subscribers = new ConcurrentHashMap<>();

    public RuleAckRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 订阅建立（watch 登记）：水位未知；同 id 重复登记 = 新代际覆盖（重连）。 */
    public Subscription register(String subscriberId) {
        State state = new State();
        subscribers.put(subscriberId, state);
        return state.subscription;
    }

    /** 订阅终止（客户端取消/服务端断流）：仅当条目仍是本代际时清除（迟到旧流注销无害）。 */
    public void evict(String subscriberId, Subscription subscription) {
        subscribers.computeIfPresent(subscriberId, (id, state) -> state.subscription == subscription ? null : state);
    }

    /** 上报已应用版本：仅更新已登记条目（未知订阅方忽略），取 max；lastAckAt 总是刷新。 */
    public void record(String subscriberId, long appliedVersion) {
        State state = subscribers.get(subscriberId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (appliedVersion > state.appliedVersion) {
                state.appliedVersion = appliedVersion;
            }
            state.lastAckAt = clock.instant();
        }
    }

    /** 当前水位视图（subscriberId 升序）。 */
    public List<AckEntry> entries() {
        return subscribers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    synchronized (entry.getValue()) {
                        Long version = entry.getValue().appliedVersion < 0 ? null : entry.getValue().appliedVersion;
                        return new AckEntry(entry.getKey(), version, entry.getValue().lastAckAt);
                    }
                })
                .toList();
    }
}
