package org.mydotey.ai.artemis.registry.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriber;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriptions;
import org.mydotey.ai.artemis.registry.domain.ServiceChange;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 投影分发（registry → discovery 数据分发，架构 §2.2 通道③、§4.2）：变更事件异步组批后
 * 推送订阅者。
 *
 * <p>消费者语义收敛（注册表内部事件 → 投影事件）：{@link ChangeType#RENEW} 过滤（投影无租约、
 * 内容未变零推送）；{@link ChangeType#EVICTED} 映射为 DELETE（清理方式是 registry 内部概念，
 * 不进分发契约）。
 *
 * <p>管线（同复制扇出的批量模式，D-P8）：ChangeListener 锁内回调非阻塞入全局有界队列 →
 * 单聚合线程 drain 当前积压、同 (service, instance) 后到覆盖先到、按 service 聚合、
 * 超 maxBatchChanges 拆批 → 投放 per-subscriber 有界队列 → 每订阅者专属线程串行回调
 * （gRPC observer 非线程安全）。无时间合批窗：稳态低流量每批即发（亚秒可见），高峰自动合批。
 *
 * <p>at-most-once（D-P4）：无重试无确认；慢消费者（订阅者队列满）或推送失败 → 服务端断流
 * 注销，消费方重 Bootstrap 全量收敛。
 */
@Slf4j
public final class ProjectionDispatcher implements ProjectionSubscriptions, AutoCloseable {

    static final String KEY_QUEUE_CAPACITY = "artemis.registry.projection.queueCapacity";
    static final String KEY_SUBSCRIBER_QUEUE_CAPACITY = "artemis.registry.projection.subscriberQueueCapacity";
    static final String KEY_MAX_BATCH_CHANGES = "artemis.registry.projection.maxBatchChanges";

    private static final int DEFAULT_QUEUE_CAPACITY = 8_192;
    private static final int DEFAULT_SUBSCRIBER_QUEUE_CAPACITY = 2_048;
    private static final int DEFAULT_MAX_BATCH_CHANGES = 1_024;

    private final LinkedBlockingQueue<InstanceChange> queue;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final int subscriberQueueCapacity;
    private final Property<String, Integer> maxBatchChanges;
    private final Counter eventsDropped;
    private final Counter subscribersDisconnected;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread aggregator;

    public ProjectionDispatcher(StringProperties properties, MeterRegistry meterRegistry) {
        this.maxBatchChanges = properties.getIntProperty(
                KEY_MAX_BATCH_CHANGES, new RangeValueConfig<>(DEFAULT_MAX_BATCH_CHANGES, 1, 1_000_000));
        this.subscriberQueueCapacity = ScfProperties.staticInt(
                        properties,
                        KEY_SUBSCRIBER_QUEUE_CAPACITY,
                        DEFAULT_SUBSCRIBER_QUEUE_CAPACITY,
                        1,
                        1_000_000,
                        "per-subscriber projection change queue capacity (static)")
                .getValue();
        this.queue = new LinkedBlockingQueue<>(ScfProperties.staticInt(
                        properties,
                        KEY_QUEUE_CAPACITY,
                        DEFAULT_QUEUE_CAPACITY,
                        1,
                        10_000_000,
                        "global projection change queue capacity (static)")
                .getValue());
        this.eventsDropped = Counter.builder("artemis.registry.projection.events.dropped")
                .description("Change events dropped when the global projection queue is full")
                .register(meterRegistry);
        this.subscribersDisconnected = Counter.builder("artemis.registry.projection.subscribers.disconnected")
                .description("Projection subscribers disconnected by the server (slow or failed push)")
                .register(meterRegistry);
    }

    /** 生命周期入口（装配层调用，等价 initMethod=start）。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("ProjectionDispatcher already started");
        }
        aggregator = Thread.ofVirtual().name("artemis-projection-aggregator").start(this::aggregate);
    }

    /**
     * 变更入口（装配层把注册表事件组合到本类；回调发生在聚合锁内）：非阻塞 offer；
     * 无订阅者零开销（消费方 bootstrap 兜底）；队列满丢当条 + 计数
     * （at-most-once，丢失由重连 bootstrap / 兜底轮询收敛）。
     */
    public void onChange(InstanceChange change) {
        if (!running.get() || change.type() == ChangeType.RENEW || subscribers.isEmpty()) {
            return;
        }
        if (!queue.offer(change)) {
            eventsDropped.increment();
            log.warn("projection queue full, change dropped: {}", change);
        }
    }

    @Override
    public void subscribe(ProjectionSubscriber subscriber) {
        Subscriber internal = new Subscriber(subscriber, subscriberQueueCapacity);
        if (!running.get()) {
            internal.close(); // 已停机：关闭流，不让调用方悬挂
            return;
        }
        internal.start();
        // 建立确认（契约：首条空批）先入队，保证任何事件都排在其后——消费方据此开始 Bootstrap
        internal.enqueue(List.of());
        subscribers.add(internal);
        if (!running.get()) {
            removeAndClose(internal); // 与 close() 竞态兜底：关闭已启动的 worker
            return;
        }
        log.info("projection subscriber registered, total={}", subscribers.size());
    }

    /** 客户端正常取消（不计数；断流指标只统计服务端强制断开）。 */
    @Override
    public void unsubscribe(ProjectionSubscriber subscriber) {
        subscribers.stream()
                .filter(internal -> internal.target == subscriber)
                .findFirst()
                .ifPresent(this::removeAndClose);
    }

    @Override
    public void close() {
        running.set(false);
        if (aggregator != null) {
            aggregator.interrupt();
        }
        for (Subscriber subscriber : subscribers) {
            removeAndClose(subscriber); // 停机关闭不计入断流指标（非慢消费者/推送失败）
        }
    }

    /** 聚合循环：take 首条 → drain 当前积压 → 合并分组拆批 → 投放全部订阅者队列。 */
    private void aggregate() {
        while (running.get()) {
            List<InstanceChange> drained = new ArrayList<>();
            try {
                drained.add(queue.take());
                queue.drainTo(drained);
            } catch (InterruptedException e) {
                return; // 关闭中断
            }
            try {
                for (List<ServiceChange> batch : buildBatches(drained, maxBatchChanges.getValue())) {
                    for (Subscriber subscriber : subscribers) {
                        subscriber.enqueue(batch);
                    }
                }
            } catch (Throwable e) {
                log.warn("projection batch building failed, changes lost to subscriber bootstrap", e);
            }
        }
    }

    /**
     * 合并分组拆批（包私有供确定性单测）：同 (service, instance) 后到覆盖先到；按 service 聚合、
     * 保持产生序；EVICTED 映射为 DELETE；批大小按变更条数封顶（单服务超限时按序切分为多个
     * 同 key 的 ServiceChange，仍保持该 key 内的产生序）。RENEW 由 {@link #onChange} 过滤，此处不出现。
     */
    static List<List<ServiceChange>> buildBatches(List<InstanceChange> drained, int maxBatch) {
        var merged = InstanceChangeMerge.latestPerInstance(drained);
        LinkedHashMap<ServiceKey, List<InstanceChange>> grouped = new LinkedHashMap<>();
        for (InstanceChange change : merged.values()) {
            ChangeType type = change.type() == ChangeType.EVICTED ? ChangeType.DELETE : change.type();
            grouped.computeIfAbsent(change.key(), key -> new ArrayList<>())
                    .add(new InstanceChange(change.key(), change.instance(), type, change.changeTimeMs()));
        }
        List<List<ServiceChange>> batches = new ArrayList<>();
        List<ServiceChange> current = new ArrayList<>();
        int count = 0;
        for (var entry : grouped.entrySet()) {
            List<InstanceChange> changes = entry.getValue();
            int offset = 0;
            while (offset < changes.size()) {
                int take = Math.min(maxBatch - count, changes.size() - offset);
                current.add(new ServiceChange(entry.getKey(), changes.subList(offset, offset + take)));
                count += take;
                offset += take;
                if (count >= maxBatch) {
                    batches.add(List.copyOf(current));
                    current = new ArrayList<>();
                    count = 0;
                }
            }
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private void removeAndClose(Subscriber subscriber) {
        if (subscribers.remove(subscriber)) {
            subscriber.close();
        }
    }

    /** 慢消费者/推送失败的服务端强制断流（计入指标）。 */
    private void disconnect(Subscriber subscriber) {
        if (subscribers.remove(subscriber)) {
            subscribersDisconnected.increment();
            subscriber.close();
        }
    }

    /** 单订阅者投递状态：有界批队列（满即断流）+ 专属串行推送线程。 */
    private final class Subscriber {

        private final ProjectionSubscriber target;
        private final LinkedBlockingQueue<List<ServiceChange>> queue;
        private final AtomicBoolean closed = new AtomicBoolean();
        /** volatile：订阅者先入列表后启动线程，close 可能由其他线程并发读。 */
        private volatile Thread worker;

        private Subscriber(ProjectionSubscriber target, int capacity) {
            this.target = target;
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        private void start() {
            worker = Thread.ofVirtual().name("artemis-projection-subscriber").start(this::deliver);
        }

        /** 单生产者（聚合线程）入队；满 = 慢消费者 → 断流注销（at-most-once，消费方 bootstrap 自愈）。 */
        private void enqueue(List<ServiceChange> batch) {
            if (!queue.offer(batch)) {
                log.warn("projection subscriber queue full, disconnecting slow consumer");
                disconnect(this);
            }
        }

        private void deliver() {
            while (!closed.get()) {
                List<ServiceChange> batch;
                try {
                    batch = queue.take();
                } catch (InterruptedException e) {
                    return; // 关闭中断
                }
                try {
                    target.onChanges(batch);
                } catch (Throwable e) {
                    log.warn("projection push failed, disconnecting subscriber", e);
                    disconnect(this);
                    return;
                }
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                if (worker != null) {
                    worker.interrupt();
                }
                try {
                    target.close();
                } catch (Throwable e) {
                    log.warn("projection subscriber close failed", e);
                }
            }
        }
    }
}
