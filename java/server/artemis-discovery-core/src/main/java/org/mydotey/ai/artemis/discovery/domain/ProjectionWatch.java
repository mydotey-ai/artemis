package org.mydotey.ai.artemis.discovery.domain;

/** 变更流订阅句柄：close 主动断流（会话 close 一并关闭）。 */
public interface ProjectionWatch extends AutoCloseable {

    @Override
    void close();
}
