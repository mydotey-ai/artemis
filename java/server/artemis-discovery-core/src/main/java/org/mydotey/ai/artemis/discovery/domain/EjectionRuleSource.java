package org.mydotey.ai.artemis.discovery.domain;

/**
 * 摘除规则源端口（出站，server 层提供 gRPC 实现）：单流 watch——快照在流内首条
 * （无「确认后 bootstrap」两段序，区别于 {@link ProjectionSource}），端点选择与轮换
 * 由实现方完成，对调用方不可见。phase-5：ack 上行已应用版本（同端点/channel 池）。
 */
public interface EjectionRuleSource {

    /** 开启规则流订阅（绑定单个上游端点）；失败重开即换端点。 */
    EjectionRuleWatch watch(EjectionRuleListener listener);

    /**
     * 上报已应用（已产生效果）的规则集版本；幂等、失败容忍（调用方 WARN 后放弃，
     * 重连快照后重新 ack 收敛）。
     */
    void ack(long appliedVersion);
}
