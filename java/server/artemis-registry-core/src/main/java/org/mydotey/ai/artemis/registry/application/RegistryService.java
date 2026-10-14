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

    /** 租约过期清理（同步单轮，返回摘除数）；由装配层注入的调度器周期触发。 */
    int evictExpired();
}
