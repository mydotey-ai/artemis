package org.mydotey.ai.artemis.discovery.domain;

/**
 * 订阅推送出口端口（D-S8/D-S9）：server 侧实现（写 gRPC 流 + 指标 + isReady 断流）。
 * 单订阅者内串行调用（表锁扇出，D-S4/FIX-1）；实现须非阻塞。
 */
public interface PushTarget {

    /** 订阅建立确认（流首条空事件，D-S1）。 */
    void established();

    /** 视图语义变更推送（per-service 单条，D-S1）。 */
    void accept(String cluster, ServiceChange change);
}
