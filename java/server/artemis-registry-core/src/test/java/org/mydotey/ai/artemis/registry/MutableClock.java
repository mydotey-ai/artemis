package org.mydotey.ai.artemis.registry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;

/** 测试设施：可控时钟（单测与 IT 通用），手动推进验证续约/TTL，零 sleep；同源驱动壁挂与单调两端口。 */
public final class MutableClock extends Clock implements MonotonicClock {

    private volatile long millis;

    public MutableClock advance(Duration duration) {
        millis += duration.toMillis();
        return this;
    }

    public MutableClock reset() {
        millis = 0L;
        return this;
    }

    public long currentMillis() {
        return millis;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public long monoMillis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }
}
