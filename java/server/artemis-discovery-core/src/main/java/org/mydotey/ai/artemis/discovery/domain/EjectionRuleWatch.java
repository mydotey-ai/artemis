package org.mydotey.ai.artemis.discovery.domain;

/** 摘除规则流订阅句柄：close 主动断流。 */
public interface EjectionRuleWatch extends AutoCloseable {

    @Override
    void close();
}
