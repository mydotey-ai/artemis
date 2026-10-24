package org.mydotey.ai.artemis.client;

/**
 * 消费方发现客户端：共享连接、前置寻址（ADR-020）与线程设施，按订阅承载各自选择器与本地视图。
 *
 * <p>生命周期：{@link #start()} 必须先于 {@link #subscribe}（否则 {@link IllegalStateException}）；
 * {@link #close()} 幂等——关闭全部订阅（各终写快照）后释放共享资源（地址寻址、连接、线程）。
 * 多订阅共享 applier/监听队列/调度线程，每订阅仅多一条 gRPC 流。
 *
 * <p>同步语义（ADR-019 推拉结合）：订阅建立（确认）后 bootstrap 全量 Query 覆盖本地视图并
 * 重放期间到达的推送；稳态实时推增量、60s checksum 对账、15min 全量刷新兜底；server 全挂时
 * 本地最后视图继续服务（陈旧换可用），磁盘快照支撑冷启动回放。
 */
public interface DiscoveryClient extends AutoCloseable {

    void start();

    /**
     * 建立订阅（选择器为 API 参数，运行期可建多个独立订阅）。
     * 订阅立即开始同步：回放该选择器磁盘快照（若有）→ 建订阅流 → bootstrap 全量。
     *
     * @throws IllegalStateException start 前或 close 后调用
     */
    Subscription subscribe(DiscoverySelector selector, DiscoveryListener listener);

    /** 无变更监听（仅本地缓存/拉取）的便捷重载。 */
    default Subscription subscribe(DiscoverySelector selector) {
        return subscribe(selector, null);
    }

    @Override
    void close();
}
