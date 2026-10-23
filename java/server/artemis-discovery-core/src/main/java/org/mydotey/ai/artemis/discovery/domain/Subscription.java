package org.mydotey.ai.artemis.discovery.domain;

/** 订阅句柄（D-S8）：close 注销登记（幂等）；流断开三路（cancel/完成/服务端断流）殊途同归。 */
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
