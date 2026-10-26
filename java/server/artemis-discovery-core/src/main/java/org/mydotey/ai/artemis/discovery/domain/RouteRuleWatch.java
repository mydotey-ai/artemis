package org.mydotey.ai.artemis.discovery.domain;

/** 路由规则流订阅句柄：close 主动断流。 */
public interface RouteRuleWatch extends AutoCloseable {

    @Override
    void close();
}
