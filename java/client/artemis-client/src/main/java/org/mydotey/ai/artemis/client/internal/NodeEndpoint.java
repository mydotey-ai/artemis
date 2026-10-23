package org.mydotey.ai.artemis.client.internal;

/**
 * 节点列表条目（前置寻址产物）：gRPC 端点 host:port + 选址优先级。
 * 优先级来自服务端观测态：UP=2、UNKNOWN/STARTING=1、DOWN=0——只作选址优先级不作排除
 * （探测有周期窗口，正确性由失败上报 + 熔断兜底）。
 */
final class NodeEndpoint {

    static final int PRIORITY_UP = 2;
    static final int PRIORITY_ALIVE = 1;
    static final int PRIORITY_DOWN = 0;

    final String address;
    final int priority;

    NodeEndpoint(String address, int priority) {
        this.address = address;
        this.priority = priority;
    }

    @Override
    public String toString() {
        return address + "(p" + priority + ")";
    }
}
