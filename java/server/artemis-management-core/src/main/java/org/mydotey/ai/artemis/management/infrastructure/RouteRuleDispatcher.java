package org.mydotey.ai.artemis.management.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRulePublisher;
import org.mydotey.ai.artemis.management.domain.RouteRuleStatus;
import org.mydotey.ai.artemis.management.domain.RouteRuleStore;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.RouteRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.RouteRules;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 路由规则分发（规则流服务端，phase-2，模式同 {@link EjectionRuleDispatcher}）：
 * subscribe（登记 + 快照）/ 增量推送在同一锁内串行——订阅建立与规则变更互斥，
 * 快照读取无「半增量」竞态。流内容 = ACTIVE 集：快照经 activeOf 过滤，增量的 ACTIVE
 * 判定收敛在 {@code onUpserted} 单点（INACTIVE 转推 deleted），应用层无条件通知。
 *
 * <p>版本 = 内存 tick（AtomicLong，每次变更 +1）：水位/观测语义非顺序协议——
 * management 重启归零，断流重连快照全量替换 + 重新 ack 后水位重新收敛（无跨重启
 * 持久 sequence 源：route-rule 变更含 CRUD，无记录表可派生，tick 即最小状态）。
 * 跳号合法、等号安全（消费方事件应用不依赖版本序）。
 *
 * <p>at-most-once：推送失败（subscriber 回调抛出）= 断流注销，消费方重连全量自愈。
 */
@Slf4j
public final class RouteRuleDispatcher implements RouteRulePublisher, RouteRuleSubscriptions, AutoCloseable {

    private final RouteRuleStore store;
    private final List<RouteRuleSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Object dispatchLock = new Object();
    private final AtomicLong tick = new AtomicLong();

    public RouteRuleDispatcher(RouteRuleStore store) {
        this.store = store;
    }

    /** ACTIVE 集不变量单点：INACTIVE 规则转推 deleted（消费方幂等），快照/增量口径一致。 */
    @Override
    public void onUpserted(RouteRule rule) {
        synchronized (dispatchLock) {
            long version = tick.incrementAndGet();
            if (rule.status() == RouteRuleStatus.INACTIVE) {
                push(subscriber -> subscriber.onDeleted(rule.service(), rule.name(), version));
            } else {
                push(subscriber -> subscriber.onUpserted(rule, version));
            }
        }
    }

    @Override
    public void onDeleted(ServiceKey service, String name) {
        synchronized (dispatchLock) {
            long version = tick.incrementAndGet();
            push(subscriber -> subscriber.onDeleted(service, name, version));
        }
    }

    @Override
    public long version() {
        return tick.get();
    }

    @Override
    public void subscribe(RouteRuleSubscriber subscriber) {
        synchronized (dispatchLock) {
            // 快照现读与登记同锁段：登记后增量必达、快照含登记时刻全量，无窗口
            List<RouteRule> active = RouteRules.activeOf(store.findAll());
            subscribers.add(subscriber);
            try {
                subscriber.onSnapshot(active, tick.get());
            } catch (RuntimeException e) {
                disconnect(subscriber, "snapshot push failed", e);
            }
        }
    }

    @Override
    public void unsubscribe(RouteRuleSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 活跃订阅数（测试/排障用）。 */
    public int size() {
        return subscribers.size();
    }

    @Override
    public void close() {
        List<RouteRuleSubscriber> remaining = List.copyOf(subscribers);
        subscribers.clear();
        for (RouteRuleSubscriber subscriber : remaining) {
            closeQuietly(subscriber);
        }
    }

    /** 锁内逐订阅者推送；失败断流注销（at-most-once）。 */
    private void push(Consumer<RouteRuleSubscriber> delivery) {
        for (RouteRuleSubscriber subscriber : subscribers) {
            try {
                delivery.accept(subscriber);
            } catch (RuntimeException e) {
                disconnect(subscriber, "push failed", e);
            }
        }
    }

    /** 断流注销：移除登记并关闭订阅者（gRPC 流关断，消费方重连快照自愈）。 */
    private void disconnect(RouteRuleSubscriber subscriber, String reason, RuntimeException cause) {
        subscribers.remove(subscriber);
        log.warn("route rule subscriber disconnected: {}", reason, cause);
        closeQuietly(subscriber);
    }

    private static void closeQuietly(RouteRuleSubscriber subscriber) {
        try {
            subscriber.close();
        } catch (RuntimeException e) {
            log.warn("route rule subscriber close failed", e);
        }
    }
}
