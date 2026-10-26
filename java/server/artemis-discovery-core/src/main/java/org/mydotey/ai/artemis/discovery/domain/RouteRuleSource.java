package org.mydotey.ai.artemis.discovery.domain;

/**
 * 路由规则源端口（出站，server 层提供 gRPC 实现；模式同 {@link EjectionRuleSource}）：
 * 单流 watch——快照在流内首条，端点选择与轮换由实现方完成；ack 上行已应用版本。
 */
public interface RouteRuleSource {

    /** 开启规则流订阅（绑定单个上游端点）；失败重开即换端点。 */
    RouteRuleWatch watch(RouteRuleListener listener);

    /**
     * 上报已应用（已产生效果）的规则集版本；幂等、失败容忍（调用方 WARN 后放弃，
     * 重连快照后重新 ack 收敛）。
     */
    void ack(long appliedVersion);
}
