package org.mydotey.ai.artemis.discovery.domain;

/**
 * 单轮同步会话（同一上游端点）。
 *
 * <p>调用序：先 {@link #watch}（收到 {@link ProjectionListener#onEstablished()} 后再
 * {@link #bootstrap()}，phase-1 契约无丢失序）。close 后不得再回调 listener。
 */
public interface ProjectionSession extends AutoCloseable {

    /** 异步订阅变更流（同一端点）。 */
    ProjectionWatch watch(String subscriberId, ProjectionListener listener);

    /** blocking 全量拉取（同一端点）；流中断/失败抛异常，由调用方整轮重来。 */
    java.util.List<ServiceInstances> bootstrap();

    @Override
    void close();
}
