package org.mydotey.ai.artemis.management.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.LogicalInstancePublisher;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceStore;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriber;
import org.mydotey.ai.artemis.management.domain.LogicalInstanceSubscriptions;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 逻辑实例流分发（模式同 {@code EjectionRuleDispatcher}）：subscribe（登记 + 快照）/
 * onUpserted / onDeleted 推送在同一锁内串行——订阅建立与管理操作互斥，快照读取无
 * 「半增量」竞态。管理操作级频率，不走队列 + 专属线程管线。
 *
 * <p>at-most-once：推送失败（subscriber 回调抛出）= 断流注销，消费方重连全量自愈。
 */
@Slf4j
public final class LogicalInstanceDispatcher
        implements LogicalInstancePublisher, LogicalInstanceSubscriptions, AutoCloseable {

    private final LogicalInstanceStore store;
    private final List<LogicalInstanceSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Object dispatchLock = new Object();

    public LogicalInstanceDispatcher(LogicalInstanceStore store) {
        this.store = store;
    }

    /** 变更落库后由应用服务调用（内容变化才推）；无订阅者零开销。 */
    public void onUpserted(LogicalInstance instance) {
        synchronized (dispatchLock) {
            push(subscriber -> subscriber.onUpserted(instance));
        }
    }

    /** 删除落库后由应用服务调用（命中才推）。 */
    public void onDeleted(ServiceKey service, String instanceId) {
        synchronized (dispatchLock) {
            push(subscriber -> subscriber.onDeleted(service, instanceId));
        }
    }

    @Override
    public void subscribe(LogicalInstanceSubscriber subscriber) {
        synchronized (dispatchLock) {
            // 快照现读与登记同锁段：登记后增量必达、快照含登记时刻全量，无窗口
            List<LogicalInstance> snapshot = store.findAll();
            subscribers.add(subscriber);
            try {
                subscriber.onSnapshot(snapshot);
            } catch (RuntimeException e) {
                disconnect(subscriber, "snapshot push failed", e);
            }
        }
    }

    @Override
    public void unsubscribe(LogicalInstanceSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 活跃订阅数（测试/排障用）。 */
    public int size() {
        return subscribers.size();
    }

    @Override
    public void close() {
        List<LogicalInstanceSubscriber> remaining = List.copyOf(subscribers);
        subscribers.clear();
        for (LogicalInstanceSubscriber subscriber : remaining) {
            closeQuietly(subscriber);
        }
    }

    /** 锁内逐订阅者推送；失败断流注销（at-most-once）。 */
    private void push(Consumer<LogicalInstanceSubscriber> delivery) {
        for (LogicalInstanceSubscriber subscriber : subscribers) {
            try {
                delivery.accept(subscriber);
            } catch (RuntimeException e) {
                disconnect(subscriber, "push failed", e);
            }
        }
    }

    /** 断流注销：移除登记并关闭订阅者（gRPC 流关断，消费方重连快照自愈）。 */
    private void disconnect(LogicalInstanceSubscriber subscriber, String reason, RuntimeException cause) {
        subscribers.remove(subscriber);
        log.warn("logical instance subscriber disconnected: {}", reason, cause);
        closeQuietly(subscriber);
    }

    private static void closeQuietly(LogicalInstanceSubscriber subscriber) {
        try {
            subscriber.close();
        } catch (RuntimeException e) {
            log.warn("logical instance subscriber close failed", e);
        }
    }
}
