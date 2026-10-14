package org.mydotey.ai.artemis.registry.domain;

/**
 * 单调时间源端口：单调递增、不受系统校时（NTP 前跳/后跳）影响，用于一切**时长判定**——
 * 租约续约时刻、过期判定、事件时刻（D14）。基准原点任意（进程内有效，跨进程/跨重启不可比），
 * 只保证同源差值即真实时长。跨进程传递的时间戳（如 serverTimeMs）不走本端口。
 */
@FunctionalInterface
public interface MonotonicClock {

    /** 单调毫秒（同源差值即时长；绝对值无跨进程语义）。 */
    long monoMillis();
}
