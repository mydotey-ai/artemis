package org.mydotey.ai.artemis.client.internal;

/**
 * 服务端地址供给端口：三级地址容灾骨架的抽象（架构 §4.1 资产 #2）。
 * v0.1 唯一实现 {@link ConfigAddressProvider}（配置列表随机选址）；存活节点列表拉取、
 * 熔断换址、TTL 强制轮换为 v0.2 扩展点。
 */
interface ServerAddressProvider {

    /** 选取一个地址（重连时调用——实现可借此轮换/熔断）。 */
    String select();
}
