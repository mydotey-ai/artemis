package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/**
 * 端口：投影推送的订阅者出口（registry → discovery 数据分发）。server 接入层以
 * gRPC {@code StreamObserver} 适配。
 *
 * <p>服务端对每个订阅者单线程串行回调（gRPC observer 非线程安全）；{@link #close()} 由服务端
 * 触发（慢消费者/推送失败/停机），实现应关闭底层流。
 */
public interface ProjectionSubscriber extends AutoCloseable {

    void onChanges(List<ServiceChange> batch);

    @Override
    void close();
}
