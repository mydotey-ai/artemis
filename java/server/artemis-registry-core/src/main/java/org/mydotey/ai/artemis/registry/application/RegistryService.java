package org.mydotey.ai.artemis.registry.application;

import java.util.Optional;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.DeregisterResponse;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponse;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.UpdateRequest;
import org.mydotey.ai.artemis.registry.domain.UpdateResponse;

/**
 * 注册表应用服务：四操作用例编排（校验/归一 → 聚合 → 组装领域响应）。
 * 请求/响应模型直接复用领域层定义。清理调度由装配层组合（application 与 infrastructure
 * 互不依赖，ADR-015）：领域端口 {@link org.mydotey.ai.artemis.registry.domain.LeaseEvictionScheduler}
 * 的 infrastructure 实现（{@code ScheduledLeaseEvictionScheduler}）周期触发 {@link #evictExpired()}——
 * 非 Spring 装配方须自行组合该端口，否则过期实例不会被清理（存在性由装配层保证）。
 */
public interface RegistryService {

    RegisterResponse register(RegisterRequest request);

    DeregisterResponse deregister(DeregisterRequest request);

    UpdateResponse update(UpdateRequest request);

    HeartbeatResponse heartbeat(HeartbeatRequest request);

    /** 读快照：共享不可变实例集；未注册 key 返回 empty。 */
    Optional<ServiceInstances> getInstances(ServiceKey key);

    /**
     * 租约过期清理（同步单轮，返回摘除数）；由装配层注入的调度器周期触发。
     * 保护态整轮跳过（返回 0，过期实例保留）——显式 deregister 不受保护。
     */
    int evictExpired();

    /** 全量快照落盘（domain 快照 → 编码（含墙钟时间戳）→ store 写入）；由快照调度器周期触发，亦可手动触发。 */
    void writeSnapshot();

    /**
     * 启动回放（架构 §4.1 三分支 ②/③）：store 读取 → 解码 → 时效判定（落盘时间距今 ≤ maxAge，
     * 回拨宽容）→ 重建注册表（回放零事件、TTL 从回放起算），返回恢复实例数。
     * 无效快照（缺失/损坏/超期）丢弃 + WARN + 返回 0（空表启动，不拒绝启动、不影响 ready）。
     */
    int restoreFromSnapshot();
}
