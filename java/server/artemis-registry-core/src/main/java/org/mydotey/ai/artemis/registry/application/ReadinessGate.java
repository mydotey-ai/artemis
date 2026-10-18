package org.mydotey.ai.artemis.registry.application;

/**
 * 就绪门（用例编排级状态，纯 JDK）：启动数据源取得前不接客户端流量（架构 §4.1 三分支——
 * v0.1 落地为快照回放尝试完毕即 ready）。单向门：markReady 后无回退（v0.2 优雅停机再议）。
 */
public final class ReadinessGate {

    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    public void markReady() {
        ready = true;
    }
}
