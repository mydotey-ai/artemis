package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.DiscoveryListener;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.TestAwait;
import org.mydotey.ai.artemis.client.TestProperties;

/** 监听分发：有界队列满丢最旧 + 计数，慢监听不阻塞调用方。 */
class ListenerDispatcherTest {

    private static final String CAPACITY_KEY = ListenerDispatcher.KEY_QUEUE_CAPACITY;
    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order").build();

    private ListenerDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        System.clearProperty(CAPACITY_KEY);
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void should_drop_oldest_and_count_when_listener_queue_full() throws Exception {
        System.setProperty(CAPACITY_KEY, "1");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        dispatcher = new ListenerDispatcher(TestProperties.systemProperties(), registry, "c1");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DiscoveryListener slow = service -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        dispatcher.notify(slow, ORDER, "s1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); // 首条进入执行
        dispatcher.notify(slow, ORDER, "s1"); // 入队
        dispatcher.notify(slow, ORDER, "s1"); // 满 → 丢最旧 + 计数

        TestAwait.await(
                "dropped counted",
                2000,
                () -> registry.counter(
                                        "artemis.client.discovery.listener.dropped",
                                        "client",
                                        "c1",
                                        "subscription",
                                        "s1")
                                .count()
                        >= 1.0);
        release.countDown();
    }
}
