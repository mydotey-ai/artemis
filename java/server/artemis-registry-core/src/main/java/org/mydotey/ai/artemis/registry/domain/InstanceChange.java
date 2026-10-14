package org.mydotey.ai.artemis.registry.domain;

/**
 * 实例变更事件：携带被操作实例内容与变更时刻。
 *
 * @param changeTimeMs 单调钟毫秒（D14，{@link MonotonicClock} 基准）：原点进程内任意（可负），
 *     非纪元时间——仅同源差值有意义，禁止跨进程比较或与壁挂时间换算
 */
public record InstanceChange(ServiceKey key, Instance instance, ChangeType type, long changeTimeMs) {}
