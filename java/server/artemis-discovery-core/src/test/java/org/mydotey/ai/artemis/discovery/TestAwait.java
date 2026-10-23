package org.mydotey.ai.artemis.discovery;

import static org.assertj.core.api.Assertions.fail;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 测试设施：有界轮询等待（生产代码禁 sleep 轮询，测试侧以 10s 上界观察异步结果）。 */
public final class TestAwait {

    private TestAwait() {}

    public static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timeout waiting: %s", what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
