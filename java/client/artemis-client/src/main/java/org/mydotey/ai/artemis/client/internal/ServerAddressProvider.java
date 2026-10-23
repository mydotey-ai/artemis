package org.mydotey.ai.artemis.client.internal;

import java.util.List;

/**
 * 服务端地址供给端口：三级地址容灾的选址面（架构 §4.1 资产 #2）。
 * 选址池 = 前置寻址（{@link NodeListFetcher}）刷新的节点列表；熔断降权不清零（全熔断全量随机）。
 */
interface ServerAddressProvider {

    /**
     * 选址：优先级组内随机。exclude 非空且排除后仍有候选时排除之（重连换址/TTL 轮换传当前地址，
     * null = 冷启动）；列表为空抛 {@link NoAddressException}（寻址未完成，调用方按瞬态处理）。
     */
    String select(String exclude);

    /** 同一节点连续传输失败上报：达阈值熔断（select 跳过）并触发重寻址回调（可空）。 */
    void markFailed(String address);

    /** 传输成功上报：清零失败计数并解除熔断。 */
    void markSucceeded(String address);

    /** 前置寻址刷新节点列表（全量替换）。 */
    void updateNodes(List<NodeEndpoint> nodes);
}
