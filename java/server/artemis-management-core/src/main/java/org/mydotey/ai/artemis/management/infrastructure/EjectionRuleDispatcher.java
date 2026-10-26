package org.mydotey.ai.artemis.management.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionRecords;
import org.mydotey.ai.artemis.management.domain.EjectionRulePublisher;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriber;
import org.mydotey.ai.artemis.management.domain.EjectionRuleSubscriptions;
import org.mydotey.ai.artemis.management.domain.EjectionStore;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;

/**
 * 摘除规则分发（规则流服务端，D-E7/D-E10）：subscribe（登记 + 快照）/ onEjected /
 * onRecovered 推送在同一锁内串行——订阅建立与摘除操作互斥，快照读取无「半增量」竞态。
 * 管理操作级频率，不采用 ProjectionDispatcher 的队列 + 专属线程管线
 * （ponytail: 若规则推送频率增长到机器级，迁移到同模式）。
 *
 * <p>快照 = 有效规则集（未对消，含四级；DB 态即跨重启恢复源）；增量由应用层仅在
 * 目标生效态翻转时触发（叠加/对消无翻转不推，防消费方误删仍生效规则）。事件携带
 * 规则集状态版本 = 触发翻转记录 sequence（phase-5 水位语义，与「无翻转不推」对齐）。
 *
 * <p>at-most-once：推送失败（subscriber 回调抛出）= 断流注销，消费方重连全量自愈。
 */
@Slf4j
public final class EjectionRuleDispatcher implements EjectionRulePublisher, EjectionRuleSubscriptions, AutoCloseable {

    private final EjectionStore store;
    private final List<EjectionRuleSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Object dispatchLock = new Object();

    public EjectionRuleDispatcher(EjectionStore store) {
        this.store = store;
    }

    /** 摘除落库后由应用服务调用（仅生效态翻转入 active 时）；无订阅者零开销。 */
    public void onEjected(EjectionRecord record) {
        synchronized (dispatchLock) {
            push(subscriber -> subscriber.onEjected(record));
        }
    }

    /** 恢复落库后由应用服务调用（仅生效态翻转入 inactive 时）。 */
    public void onRecovered(EjectionTarget target, long version) {
        synchronized (dispatchLock) {
            push(subscriber -> subscriber.onRecovered(target, version));
        }
    }

    @Override
    public void subscribe(EjectionRuleSubscriber subscriber) {
        synchronized (dispatchLock) {
            // 快照现读与登记同锁段：登记后增量必达、快照含登记时刻全量，无窗口
            List<EjectionRecord> records = store.findAll();
            List<EjectionRecord> snapshot = EjectionRecords.activeOf(records);
            subscribers.add(subscriber);
            try {
                subscriber.onSnapshot(snapshot, EjectionRecords.lastFlipSequence(records));
            } catch (RuntimeException e) {
                disconnect(subscriber, "snapshot push failed", e);
            }
        }
    }

    @Override
    public void unsubscribe(EjectionRuleSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 活跃订阅数（测试/排障用）。 */
    public int size() {
        return subscribers.size();
    }

    @Override
    public void close() {
        List<EjectionRuleSubscriber> remaining = List.copyOf(subscribers);
        subscribers.clear();
        for (EjectionRuleSubscriber subscriber : remaining) {
            closeQuietly(subscriber);
        }
    }

    /** 锁内逐订阅者推送；失败断流注销（at-most-once）。 */
    private void push(java.util.function.Consumer<EjectionRuleSubscriber> delivery) {
        for (EjectionRuleSubscriber subscriber : subscribers) {
            try {
                delivery.accept(subscriber);
            } catch (RuntimeException e) {
                disconnect(subscriber, "push failed", e);
            }
        }
    }

    /** 断流注销：移除登记并关闭订阅者（gRPC 流关断，消费方重连快照自愈）。 */
    private void disconnect(EjectionRuleSubscriber subscriber, String reason, RuntimeException cause) {
        subscribers.remove(subscriber);
        log.warn("ejection rule subscriber disconnected: {}", reason, cause);
        closeQuietly(subscriber);
    }

    private static void closeQuietly(EjectionRuleSubscriber subscriber) {
        try {
            subscriber.close();
        } catch (RuntimeException e) {
            log.warn("ejection rule subscriber close failed", e);
        }
    }
}
