package org.mydotey.ai.artemis.registry;

import static org.assertj.core.api.Assertions.fail;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 测试设施：有界轮询等待（单测与 IT 通用，随 test-jar 分发）。
 * 生产代码禁 sleep 轮询（tech-java），测试侧以 10s 上界轮询观察异步/调度结果。
 */
public final class TestAwait {

    private TestAwait() {}

    public static void await(String what, BooleanSupplier condition) {
        await(what, 10_000, condition);
    }

    /** 显式超时（毫秒）：集群 IT 高负载场景放宽观察窗口。 */
    public static void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
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
