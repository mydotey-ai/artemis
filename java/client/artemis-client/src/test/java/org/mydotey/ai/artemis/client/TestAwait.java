package org.mydotey.ai.artemis.client;

import java.util.function.BooleanSupplier;

/** 测试侧有界轮询等待（10ms 间隔；测试代码允许轮询，产品代码禁）。 */
public final class TestAwait {

    private TestAwait() {}

    public static void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting: " + what, e);
            }
        }
        throw new AssertionError("timeout waiting: " + what);
    }
}
