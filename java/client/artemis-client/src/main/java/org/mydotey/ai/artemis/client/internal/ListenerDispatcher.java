package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mydotey.ai.artemis.client.DiscoveryListener;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅回调分发（client 级共享单线程 + 有界队列，legacy 局限 #13 修复）：满时丢最旧并计数，
 * 慢监听不阻塞推送/applier。监听异常 catch + WARN，不影响其它回调。
 */
final class ListenerDispatcher {

    static final String KEY_QUEUE_CAPACITY = "artemis.client.discovery.listener.queueCapacity";

    private static final Logger LOG = LoggerFactory.getLogger(ListenerDispatcher.class);

    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    private static final class Notification {
        final DiscoveryListener listener;
        final ServiceId service;
        final String subscriptionTag;

        Notification(DiscoveryListener listener, ServiceId service, String subscriptionTag) {
            this.listener = listener;
            this.service = service;
            this.subscriptionTag = subscriptionTag;
        }
    }

    private final BlockingQueue<Notification> queue;
    private final MeterRegistry registry;
    private final String clientId;
    private final Thread thread;
    private final AtomicBoolean closed = new AtomicBoolean();

    ListenerDispatcher(StringProperties properties, MeterRegistry registry, String clientId) {
        Property<String, Integer> capacity = properties.getIntProperty(
                KEY_QUEUE_CAPACITY, new RangeValueConfig<>(DEFAULT_QUEUE_CAPACITY, 1, 100_000));
        this.queue = new ArrayBlockingQueue<>(capacity.getValue());
        this.registry = registry;
        this.clientId = clientId;
        this.thread = new Thread(this::run, "artemis-client-discovery-listener");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void notify(DiscoveryListener listener, ServiceId service, String subscriptionTag) {
        if (listener == null || closed.get()) {
            return;
        }
        Notification notification = new Notification(listener, service, subscriptionTag);
        if (!queue.offer(notification)) {
            queue.poll(); // 丢最旧
            queue.offer(notification);
            registry.counter(
                            "artemis.client.discovery.listener.dropped",
                            "client",
                            clientId,
                            "subscription",
                            subscriptionTag)
                    .increment();
        }
    }

    private void run() {
        while (!closed.get()) {
            Notification notification;
            try {
                notification = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                notification.listener.onServiceChanged(notification.service);
            } catch (Throwable e) {
                LOG.warn("discovery listener failed for {}: {}", notification.service, e.toString());
            }
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
