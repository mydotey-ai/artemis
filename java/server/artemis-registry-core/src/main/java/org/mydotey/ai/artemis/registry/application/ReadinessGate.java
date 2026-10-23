package org.mydotey.ai.artemis.registry.application;

/**
 * 就绪门（用例编排级状态，纯 JDK）：启动数据源取得前不接客户端流量（架构 §4.1 三分支——
 * v0.1 落地为快照回放尝试完毕即 ready）。
 *
 * <p>三态（v0.2 phase-6 优雅停机）：NOT_READY → READY → DOWN，DOWN 优先级最高且终态——
 * {@link #markDown()} 幂等，DOWN 后 {@link #markReady()} 无效（停机节点不复活）；
 * {@link #isReady()} 仅 READY 为真，未就绪窗口与停机排水窗口同走既有 readiness 联动
 * （外部 rpc 拦截 / Replicate·Bootstrap UNAVAILABLE / HTTP 门控）。
 */
public final class ReadinessGate {

    private volatile boolean ready;

    private volatile boolean down;

    public boolean isReady() {
        return ready && !down;
    }

    public boolean isDown() {
        return down;
    }

    public void markReady() {
        if (!down) {
            ready = true;
        }
    }

    public void markDown() {
        down = true;
    }
}
