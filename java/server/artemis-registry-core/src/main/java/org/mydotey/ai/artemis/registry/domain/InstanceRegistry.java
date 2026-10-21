package org.mydotey.ai.artemis.registry.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.filter.RangeValueConfig;

/**
 * 注册表聚合：四操作语义 + 租约过期清理（ADR-012、架构 §4.1）。
 *
 * <p>存储结构（guidelines §4.1）：CHM&lt;ServiceKey, ServiceHolder&gt;，holder 内 CHM&lt;instanceId, Lease&gt;
 * + volatile 共享快照。快照仅在成员/内容变更时重建（holder 锁内），纯续约只写 volatile 续约时刻，
 * 稳态零快照分配；读路径无锁返回共享不可变引用。
 *
 * <p>事件序（D13）：同 key 操作在 holder 锁内全同步——写存储、重建快照、派发事件、回收空 holder
 * 依次完成（{@link #commit} 单一提交尾），事件到达顺序即存储变更顺序；快照重建不因「摘除后变空」跳过，
 * 事件派发期间读到的快照与已派发事件一致。holder 回收后置于事件派发，新操作在锁上排队、经乐观重试
 * 换新 holder 后其事件必然晚于回收者的 DELETE（无幻影终态事件）。
 *
 * <p>listener 契约（锁内回调）：须非阻塞、不得跨 key 回调注册表（D4 纪律；synchronized 可重入，
 * 同 key 读安全）；单事件异常被隔离并降级为日志——存储不变量（快照一致、空 holder 回收、批内剩余
 * 事件）不依赖 listener 行为。
 *
 * <p>时间源（D14）：一切时长判定（续约时刻、过期判定、事件时刻）取 {@link MonotonicClock}
 * （单调递增，不受 NTP 校时影响）；壁挂钟不进聚合。
 */
@Slf4j
public final class InstanceRegistry {

    public static final String KEY_LEASE_TTL_MS = "artemis.registry.lease.ttlMs";

    private static final long DEFAULT_LEASE_TTL_MS = 30_000;

    private final ConcurrentHashMap<ServiceKey, ServiceHolder> services = new ConcurrentHashMap<>();
    private final MonotonicClock monotonicClock;
    private final ChangeListener listener;
    /** 动态 TTL property（class 字段，使用处每次取最新值）；定义在本类（租约 TTL 的消费方）。 */
    private final Property<String, Long> leaseTtlMs;

    public InstanceRegistry(StringProperties properties, MonotonicClock monotonicClock, ChangeListener listener) {
        this.leaseTtlMs = properties.getLongProperty(
                KEY_LEASE_TTL_MS, new RangeValueConfig<>(DEFAULT_LEASE_TTL_MS, 1_000L, 600_000L));
        this.monotonicClock = monotonicClock;
        this.listener = listener;
    }

    /**
     * register：无条件覆盖（不比较内容），逐实例发 NEW。
     * 空分组 no-op（防残留空 holder；应用层已过滤，此处为聚合入口不变量守卫）。
     */
    public void register(ServiceKey key, List<Instance> instances) {
        if (instances.isEmpty()) {
            return;
        }
        apply(key, holder -> {
            long now = monotonicClock.monoMillis();
            List<InstanceChange> changes = new ArrayList<>(instances.size());
            for (Instance instance : instances) {
                holder.leases.put(instance.instanceId(), new Lease(instance, now));
                changes.add(new InstanceChange(key, instance, ChangeType.NEW, now));
            }
            return changes;
        });
    }

    /** deregister：意图幂等。返回实际摘除的 instanceId（缺席目标无事件）。 */
    public List<String> deregister(ServiceKey key, List<String> instanceIds) {
        while (true) {
            ServiceHolder holder = services.get(key);
            if (holder == null) {
                return List.of();
            }
            synchronized (holder) {
                if (services.get(key) != holder) {
                    continue; // 回收竞态：旧 holder 的事件已由回收者派发完毕，换新 holder 重试
                }
                long now = monotonicClock.monoMillis();
                List<InstanceChange> changes = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                for (String instanceId : instanceIds) {
                    Lease lease = holder.leases.remove(instanceId);
                    if (lease != null) {
                        removed.add(instanceId);
                        changes.add(new InstanceChange(key, lease.instance, ChangeType.DELETE, now));
                    }
                }
                commit(key, holder, changes);
                return removed;
            }
        }
    }

    /** update：幂等 upsert。命中全量覆盖发 UPDATE，缺席建租约发 NEW。空分组 no-op（同 {@link #register}）。 */
    public void update(ServiceKey key, List<Instance> instances) {
        if (instances.isEmpty()) {
            return;
        }
        apply(key, holder -> {
            long now = monotonicClock.monoMillis();
            List<InstanceChange> changes = new ArrayList<>(instances.size());
            for (Instance instance : instances) {
                boolean hit = holder.leases.containsKey(instance.instanceId());
                holder.leases.put(instance.instanceId(), new Lease(instance, now));
                changes.add(new InstanceChange(key, instance, hit ? ChangeType.UPDATE : ChangeType.NEW, now));
            }
            return changes;
        });
    }

    /**
     * 心跳对账（逐实例，不做集合 diff）：缺席 upsert 发 NEW；内容变化覆盖发 UPDATE；相同纯续约发 RENEW
     * （集群模式扇出维持 peer 副本租约，单节点 listener 为 no-op；副本租约语义见架构 §4.1）。
     * 空分组 no-op（同 {@link #register}）。
     */
    public void heartbeatUpsert(ServiceKey key, List<Instance> instances) {
        if (instances.isEmpty()) {
            return;
        }
        apply(key, holder -> {
            long now = monotonicClock.monoMillis();
            List<InstanceChange> changes = new ArrayList<>(instances.size());
            for (Instance instance : instances) {
                Lease lease = holder.leases.get(instance.instanceId());
                if (lease == null) {
                    holder.leases.put(instance.instanceId(), new Lease(instance, now));
                    changes.add(new InstanceChange(key, instance, ChangeType.NEW, now));
                } else if (!lease.instance.equals(instance)) {
                    holder.leases.put(instance.instanceId(), new Lease(instance, now));
                    changes.add(new InstanceChange(key, instance, ChangeType.UPDATE, now));
                } else {
                    lease.renewalTimeMs = now;
                    changes.add(new InstanceChange(key, instance, ChangeType.RENEW, now));
                }
            }
            return changes;
        });
    }

    /**
     * 过期清理：now - 续约时刻 &gt;= TTL 即摘除（TTL 每轮读取，动态生效）并本地删除；
     * **不派发事件**（原产品继承：TTL 清理产生的 DELETE 不扇出——副本与事实源同一种租约、
     * 靠 RENEW 复制维持，收不到续约的租约在全集群各自过期，删除不跨节点传播）。返回摘除数。
     */
    // ponytail: 全表 O(服务数×实例数) 扫描，10 万实例按 1s 周期实测容量；成瓶颈改按到期时间分桶，
    // 或每 holder 维护 volatile 最早续约下界实现无锁跳过（消每秒持锁扫描对同 holder 心跳续约的排队尖刺）
    public int evictExpired() {
        long now = monotonicClock.monoMillis();
        long ttlMs = leaseTtlMs.getValue();
        int evicted = 0;
        for (Map.Entry<ServiceKey, ServiceHolder> entry : services.entrySet()) {
            ServiceKey key = entry.getKey();
            ServiceHolder holder = entry.getValue();
            synchronized (holder) {
                if (services.get(key) != holder) {
                    continue; // 迭代快照与回收竞态：旧 holder 的事件已由回收者派发完毕
                }
                boolean structural = false;
                for (Lease lease : holder.leases.values()) {
                    if (now - lease.renewalTimeMs >= ttlMs) {
                        holder.leases.remove(lease.instance.instanceId()); // CHM 弱一致迭代器容忍遍历中移除
                        structural = true;
                        evicted++;
                    }
                }
                if (structural) {
                    holder.rebuildSnapshot();
                    if (holder.leases.isEmpty()) {
                        services.remove(key, holder);
                    }
                }
            }
        }
        return evicted;
    }

    /** 读快照：共享不可变引用，零克隆；未注册 key 返回 empty。 */
    public Optional<ServiceInstances> snapshot(ServiceKey key) {
        ServiceHolder holder = services.get(key);
        return holder == null ? Optional.empty() : Optional.of(holder.snapshot);
    }

    /**
     * 全量租约明细（排障用，弱一致读，不加锁）。age/remaining TTL 在聚合内按单调钟计算，
     * 不对外暴露单调时刻。
     */
    // ponytail: 全量扫描 O(实例数)，排障端点非热路径；需要按 service 查询/分页时再加参数
    public List<LeaseDetail> leaseDetails() {
        long now = monotonicClock.monoMillis();
        long ttlMs = leaseTtlMs.getValue();
        List<LeaseDetail> details = new ArrayList<>();
        for (Map.Entry<ServiceKey, ServiceHolder> entry : services.entrySet()) {
            ServiceKey key = entry.getKey();
            for (Lease lease : entry.getValue().leases.values()) {
                // 扫描期间并发续约可使 renewalTimeMs 晚于扫描起点：age 下限钳为 0，避免负值/超 TTL
                long ageMs = Math.max(0L, now - lease.renewalTimeMs);
                details.add(new LeaseDetail(key, lease.instance.instanceId(), ageMs, ttlMs - ageMs));
            }
        }
        return details;
    }

    /** 当前实例总数（指标 gauge；弱一致聚合读，不加锁）。 */
    public int instanceCount() {
        int count = 0;
        for (ServiceHolder holder : services.values()) {
            count += holder.leases.size();
        }
        return count;
    }

    /** 全量快照读：全部 service 的共享快照聚合（供快照落盘），零克隆。 */
    public List<ServiceInstances> snapshots() {
        List<ServiceInstances> result = new ArrayList<>(services.size());
        for (ServiceHolder holder : services.values()) {
            result.add(holder.snapshot);
        }
        return result;
    }

    /**
     * 回放写（快照/peer 同步数据的重建入口，D4）：逐 service 重建租约，续约时刻 = now
     * （TTL 从回放起算），**不派发事件**——「回放零事件」为 v0.2 复制在位后的不变量。
     * 空 service 跳过（不建空 holder）。
     */
    public void restore(List<ServiceInstances> toRestore) {
        long now = monotonicClock.monoMillis();
        for (ServiceInstances service : toRestore) {
            if (service.instances().isEmpty()) {
                continue;
            }
            while (true) {
                ServiceHolder holder = services.computeIfAbsent(service.key(), ServiceHolder::new);
                synchronized (holder) {
                    if (services.get(service.key()) != holder) {
                        continue; // 回收竞态：换新 holder 重试（同 apply）
                    }
                    for (Instance instance : service.instances()) {
                        holder.leases.put(instance.instanceId(), new Lease(instance, now));
                    }
                    holder.rebuildSnapshot();
                }
                break;
            }
        }
    }

    /**
     * 复制写（peer 增量事件的应用入口，原产品继承的副本租约语义）：NEW/UPDATE 覆盖写真实租约；
     * RENEW 命中续约、缺席补注册重建（分区恢复路径）；DELETE 幂等移除。
     * **不派发事件**（防回环，同 {@link #restore} 零事件不变量）。返回实际应用的变更数。
     */
    public int applyReplicated(List<InstanceChange> changes) {
        if (changes.isEmpty()) {
            return 0;
        }
        Map<ServiceKey, List<InstanceChange>> byService = new LinkedHashMap<>();
        for (InstanceChange change : changes) {
            byService.computeIfAbsent(change.key(), key -> new ArrayList<>()).add(change);
        }
        int applied = 0;
        for (Map.Entry<ServiceKey, List<InstanceChange>> entry : byService.entrySet()) {
            applied += applyReplicated(entry.getKey(), entry.getValue());
        }
        return applied;
    }

    private int applyReplicated(ServiceKey key, List<InstanceChange> changes) {
        while (true) {
            ServiceHolder holder = services.computeIfAbsent(key, ServiceHolder::new);
            synchronized (holder) {
                if (services.get(key) != holder) {
                    continue; // 回收竞态：换新 holder 重试（同 apply）
                }
                long now = monotonicClock.monoMillis();
                int applied = 0;
                boolean structural = false;
                for (InstanceChange change : changes) {
                    String instanceId = change.instance().instanceId();
                    switch (change.type()) {
                        case NEW, UPDATE -> {
                            holder.leases.put(instanceId, new Lease(change.instance(), now));
                            structural = true;
                        }
                        case RENEW -> {
                            Lease lease = holder.leases.get(instanceId);
                            if (lease == null) {
                                holder.leases.put(instanceId, new Lease(change.instance(), now));
                                structural = true;
                            } else {
                                lease.renewalTimeMs = now;
                            }
                        }
                        case DELETE -> structural |= holder.leases.remove(instanceId) != null;
                    }
                    applied++;
                }
                if (structural) {
                    holder.rebuildSnapshot();
                }
                if (holder.leases.isEmpty()) {
                    services.remove(key, holder); // 含全 DELETE 与 no-op 空分组（不留空 holder）
                }
                return applied;
            }
        }
    }

    /**
     * 结构变更入口：锁内确认 holder 仍登记在 map（与空 service 回收的 remove 竞态时乐观重试——
     * 重试拿到的新 holder 必然建于回收者派发事件之后），写存储后走统一提交尾（D13）。
     */
    private void apply(ServiceKey key, HolderOperation operation) {
        while (true) {
            ServiceHolder holder = services.computeIfAbsent(key, ServiceHolder::new);
            synchronized (holder) {
                if (services.get(key) != holder) {
                    continue;
                }
                commit(key, holder, operation.apply(holder));
            }
            return;
        }
    }

    /**
     * 统一提交尾（须在 holder 锁内，D13）：重建快照（仅结构性变更——RENEW 纯续约零快照分配；
     * 不因摘除后变空跳过——派发期间读到的快照与事件一致）→ 派发事件（回收后置，事件序 =
     * 存储序）→ 回收空 holder。
     */
    private void commit(ServiceKey key, ServiceHolder holder, List<InstanceChange> changes) {
        if (changes.stream().anyMatch(change -> change.type() != ChangeType.RENEW)) {
            holder.rebuildSnapshot();
        }
        fire(changes);
        if (holder.leases.isEmpty()) {
            services.remove(key, holder);
        }
    }

    /** 逐事件隔离派发：listener 异常降级为日志，不中断批内剩余事件、不影响存储不变量。 */
    private void fire(List<InstanceChange> changes) {
        for (InstanceChange change : changes) {
            try {
                listener.onChange(change);
            } catch (Throwable e) {
                log.warn("change listener failed for change {}", change, e);
            }
        }
    }

    @FunctionalInterface
    private interface HolderOperation {
        List<InstanceChange> apply(ServiceHolder holder);
    }

    private static final class ServiceHolder {
        private final ServiceKey key;
        private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
        private volatile ServiceInstances snapshot;

        private ServiceHolder(ServiceKey key) {
            this.key = key;
            this.snapshot = new ServiceInstances(key, List.of());
        }

        private void rebuildSnapshot() {
            snapshot = new ServiceInstances(
                    key, leases.values().stream().map(lease -> lease.instance).toList());
        }
    }

    private static final class Lease {
        private final Instance instance;
        private volatile long renewalTimeMs;

        private Lease(Instance instance, long renewalTimeMs) {
            this.instance = instance;
            this.renewalTimeMs = renewalTimeMs;
        }
    }
}
