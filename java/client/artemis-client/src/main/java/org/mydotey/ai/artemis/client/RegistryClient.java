package org.mydotey.ai.artemis.client;

/**
 * Artemis 注册侧 SDK 客户端：本地实例集唯一事实源 + 心跳全量上报（显式四操作的客户端侧落地）。
 *
 * <p>生命周期：{@link #start()} 必须先于任何实例操作（否则 {@link IllegalStateException}）；
 * {@link #close()} 优雅下线：停心跳 → 注销全部本地实例（尽力而为）→ 释放资源，幂等。
 *
 * <p>操作语义（fire-and-forget）：操作先更新本地集（同步、必成）后**异步**上报，立即返回——
 * 成败由 SDK 内部保证最终达成：上报失败自动补投即时心跳（服务端 upsert 幂等收敛），并由周期
 * 心跳无限兜底（架构 §4.1「SDK 无需重试状态机」——心跳即重试）。失败不向用户抛出或返回，
 * 代之以 WARN 日志与 Micrometer 指标计数（见 ArtemisClients 指标集说明）；用户无需（也无法）判断操作结果。
 */
public interface RegistryClient extends AutoCloseable {

    void start();

    /** 注册：无条件覆盖（幂等）；本地集更新即刻生效，服务端最终一致。 */
    void register(ServiceInstance instance);

    /** 更新：幂等 upsert——已存在则全量覆盖，不存在则自动注册。 */
    void update(ServiceInstance instance);

    /** 反注册：意图语义——本地集即刻移除；上报丢失由服务端租约 TTL 过期收敛。 */
    void deregister(InstanceId instance);

    @Override
    void close();
}
