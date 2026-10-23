package org.mydotey.ai.artemis.registry.domain;

/** 端口：投影订阅注册/注销（infrastructure 实现分发队列与推送线程）。 */
public interface ProjectionSubscriptions {

    void subscribe(ProjectionSubscriber subscriber);

    void unsubscribe(ProjectionSubscriber subscriber);
}
