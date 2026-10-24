package org.mydotey.ai.artemis.discovery.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 订阅管理与扇出（D-S4–D-S7）：登记表弱一致迭代（低频订阅变更 + 高频扇出遍历，读路径无锁）。
 *
 * <p><b>表锁（code-review FIX-1）</b>：{@code subscribe}（登记 + 基线）与同步线程的
 * 「批扇出 + 投影应用」（{@link #runWithFanout}）互斥——基线读投影不得见批中间态（变更已推送、
 * 尚未 apply 的窗口会使基线重推已删除实例 = 僵尸复活），且确认事件必为流首条。批处理纯内存、
 * 订阅低频，表锁串行代价可忽略；订阅者视角等价单线程消费，重复推送幂等安全。
 *
 * <p>翻译（D-S3）：labels 订阅收到的是视图语义变更——匹配态矩阵把实例进出视图翻译为
 * NEW/DELETE，SDK 无脑 apply；旧匹配态经 {@code oldValues} lookup 取得（稳态 = 投影当前值
 * 于 apply 前，覆盖 = 覆盖前旧投影），「旧值在本批应用前」由调用方（同步线程）保证。
 *
 * <p>过滤链（D-E1/D-E6）：视图判定 = labels 匹配 && chain.isVisible（基线/增量同链同语义）；
 * 规则变更扇出走 {@link #onViewChanges}（视图语义直投，chain 不再判定——batch 已是视图效果）。
 */
public final class SubscriptionManager {

    static final String UNKNOWN_SUBSCRIBER = "unknown";

    private final ProjectionRegistry registry;
    private final DiscoveryFilterChain chain;
    private final Clock clock;
    private final AtomicLong nextId = new AtomicLong();
    private final ConcurrentHashMap<SubscriptionImpl, DiscoverySelector> subscriptions = new ConcurrentHashMap<>();
    private final Object fanoutLock = new Object();

    public SubscriptionManager(ProjectionRegistry registry) {
        this(registry, DiscoveryFilterChain.empty());
    }

    public SubscriptionManager(ProjectionRegistry registry, DiscoveryFilterChain chain) {
        this(registry, chain, Clock.systemUTC());
    }

    public SubscriptionManager(ProjectionRegistry registry, DiscoveryFilterChain chain, Clock clock) {
        this.registry = registry;
        this.chain = chain;
        this.clock = clock;
    }

    /** 订阅登记 + 确认 + 基线（表锁内，D-S6）：基线 = 选择器命中的当前实例集全 NEW 批。 */
    public Subscription subscribe(DiscoverySelector selector, PushTarget target) {
        return subscribe(selector, target, UNKNOWN_SUBSCRIBER);
    }

    /** 登记并记录调用方标识（管理面订阅关系，D-A2）：subscriber 空白按 unknown 归一。 */
    public Subscription subscribe(DiscoverySelector selector, PushTarget target, String subscriber) {
        String caller = subscriber == null || subscriber.isBlank() ? UNKNOWN_SUBSCRIBER : subscriber;
        SubscriptionImpl subscription =
                new SubscriptionImpl(nextId.incrementAndGet(), selector, target, caller, Instant.now(clock));
        synchronized (fanoutLock) {
            subscriptions.put(subscription, selector);
            try {
                subscription.baseline();
            } catch (RuntimeException e) {
                subscriptions.remove(subscription); // 基线失败不留登记（无句柄返回，事后无法清理）
                throw e;
            }
        }
        return subscription;
    }

    /**
     * 变更扇出（同步线程、投影变更前调用）：per subscription 翻译后逐 service 分条推送。
     *
     * @param oldValues 返回该服务本批应用前条目（可能 null），labels 匹配态判定依据
     */
    public void onChanges(String cluster, List<ServiceChange> batch, Function<ServiceKey, ServiceInstances> oldValues) {
        if (subscriptions.isEmpty()) {
            return;
        }
        // 旧值索引 per call memo（code-review FIX-4）：线性查找 O(instances×changes) → 建图 O(1) 查
        Map<ServiceKey, Map<String, Instance>> oldIndex = new HashMap<>();
        Function<ServiceKey, Map<String, Instance>> index =
                key -> oldIndex.computeIfAbsent(key, k -> index(oldValues.apply(k)));
        synchronized (fanoutLock) {
            for (Map.Entry<SubscriptionImpl, DiscoverySelector> entry : subscriptions.entrySet()) {
                entry.getKey().deliver(cluster, batch, index);
            }
        }
    }

    /** 同步线程在表锁内执行「扇出 + 投影应用」（与订阅基线互斥，批中间态对基线不可见）。 */
    public void runWithFanout(Runnable fanoutAndApply) {
        synchronized (fanoutLock) {
            fanoutAndApply.run();
        }
    }

    /**
     * 视图语义变更直投（规则变更扇出，D-E6）：batch 已是视图效果（ejected-now → DELETE、
     * recovered-now → NEW，DELETE 携带投影实例完整数据），chain 不再判定；labels/cluster/
     * services 过滤照做。表锁内调用（经 {@link #runWithFanout}），与投影扇出/基线互斥。
     */
    public void onViewChanges(String cluster, List<ServiceChange> batch) {
        if (subscriptions.isEmpty()) {
            return;
        }
        synchronized (fanoutLock) {
            for (Map.Entry<SubscriptionImpl, DiscoverySelector> entry : subscriptions.entrySet()) {
                entry.getKey().deliverViewChanges(cluster, batch);
            }
        }
    }

    /** 活跃订阅数（指标/排障用）。 */
    public int size() {
        return subscriptions.size();
    }

    /** 活跃订阅快照（管理面，D-A3）：弱一致迭代、按 id 升序；关闭即消失，无历史。 */
    public List<SubscriptionInfo> snapshot() {
        List<SubscriptionInfo> snapshot = new ArrayList<>(subscriptions.size());
        for (SubscriptionImpl subscription : subscriptions.keySet()) {
            snapshot.add(subscription.info());
        }
        snapshot.sort(Comparator.comparingLong(SubscriptionInfo::id));
        return List.copyOf(snapshot);
    }

    private static Map<String, Instance> index(ServiceInstances service) {
        if (service == null) {
            return Map.of();
        }
        Map<String, Instance> byId = new HashMap<>();
        for (Instance instance : service.instances()) {
            byId.put(instance.instanceId(), instance);
        }
        return byId;
    }

    private final class SubscriptionImpl implements Subscription {

        private final long id;
        private final DiscoverySelector selector;
        private final PushTarget target;
        private final String subscriber;
        private final Instant establishedAt;
        private volatile boolean closed;

        SubscriptionImpl(
                long id, DiscoverySelector selector, PushTarget target, String subscriber, Instant establishedAt) {
            this.id = id;
            this.selector = selector;
            this.target = target;
            this.subscriber = subscriber;
            this.establishedAt = establishedAt;
        }

        SubscriptionInfo info() {
            return new SubscriptionInfo(id, selector, subscriber, establishedAt);
        }

        @Override
        public void close() {
            closed = true;
            subscriptions.remove(this);
        }

        /** 基线：确认 + 命中条目全 NEW（per-service 分条；过滤后全空的命中条目无变更可推）。 */
        private void baseline() {
            if (closed) {
                return;
            }
            target.established();
            for (ClusterServiceInstances service : registry.query(selector, chain)) {
                if (!service.instances().instances().isEmpty()) {
                    target.accept(service.cluster(), toNew(service.instances()));
                }
            }
        }

        private void deliver(
                String cluster, List<ServiceChange> batch, Function<ServiceKey, Map<String, Instance>> oldIndex) {
            if (!selector.matchesCluster(cluster)) {
                return; // 订阅 cluster 过滤（phase-5）：非命中来源不投递（基线经 query 已过滤）
            }
            Set<ServiceKey> services = selector.services();
            for (ServiceChange service : batch) {
                if (!services.isEmpty() && !services.contains(service.key())) {
                    continue;
                }
                ServiceChange translated = translate(service, oldIndex.apply(service.key()));
                if (translated != null && !closed) { // closed 双层守卫：close 无锁，target 侧终态拦截兜底
                    target.accept(cluster, translated);
                }
            }
        }

        /** 规则变更扇出（D-E6）：视图语义直投，只做 labels 过滤（chain 效果已在 batch 内）。 */
        private void deliverViewChanges(String cluster, List<ServiceChange> batch) {
            if (!selector.matchesCluster(cluster)) {
                return;
            }
            Set<ServiceKey> services = selector.services();
            for (ServiceChange service : batch) {
                if (!services.isEmpty() && !services.contains(service.key())) {
                    continue;
                }
                List<InstanceChange> changes = null;
                for (InstanceChange change : service.changes()) {
                    if (change.type() == ChangeType.UNKNOWN || !matchesLabels(change.instance())) {
                        continue;
                    }
                    if (changes == null) {
                        changes = new ArrayList<>();
                    }
                    changes.add(change);
                }
                if (changes != null && !changes.isEmpty() && !closed) {
                    target.accept(cluster, new ServiceChange(service.key(), changes));
                }
            }
        }

        /** labels 视图语义翻译（D-S3）：新值匹配态定 NEW/UPDATE，旧值匹配态定退出视图 DELETE。 */
        private ServiceChange translate(ServiceChange service, Map<String, Instance> oldById) {
            List<InstanceChange> out = null;
            for (InstanceChange change : service.changes()) {
                InstanceChange translated = switch (change.type()) {
                    case NEW, UPDATE ->
                        translateUpsert(
                                service.key(),
                                change,
                                oldById.get(change.instance().instanceId()));
                    case DELETE ->
                        translateDelete(
                                service.key(),
                                change,
                                oldById.get(change.instance().instanceId()));
                    case UNKNOWN -> null; // 不可执行，丢弃（语义同投影 apply）
                };
                if (translated != null) {
                    if (out == null) {
                        out = new ArrayList<>();
                    }
                    out.add(translated);
                }
            }
            return out == null || out.isEmpty() ? null : new ServiceChange(service.key(), out);
        }

        /** NEW/UPDATE → 视图语义：进入视图 NEW / 视图内更新 UPDATE / 退出视图 DELETE（携带旧实例）。 */
        private InstanceChange translateUpsert(ServiceKey key, InstanceChange change, Instance previous) {
            boolean oldMatch = previous != null && matches(key, previous);
            if (change.type() == ChangeType.NEW) {
                return matches(key, change.instance()) ? new InstanceChange(change.instance(), ChangeType.NEW) : null;
            }
            if (matches(key, change.instance())) {
                // UPDATE：旧值曾入视图 = 视图内更新；否则 = 视图加入（NEW）
                return new InstanceChange(change.instance(), oldMatch ? ChangeType.UPDATE : ChangeType.NEW);
            }
            return oldMatch ? new InstanceChange(previous, ChangeType.DELETE) : null;
        }

        private InstanceChange translateDelete(ServiceKey key, InstanceChange change, Instance previous) {
            // DELETE 的 change.instance 只带 instanceId，匹配判定用旧实例完整字段
            return previous != null && matches(key, previous) ? new InstanceChange(previous, ChangeType.DELETE) : null;
        }

        /** 视图判定（D-E1）：labels 匹配 && 过滤链可见。 */
        private boolean matches(ServiceKey key, Instance instance) {
            return matchesLabels(instance) && chain.isVisible(key, instance);
        }

        /** labels 匹配（不含 chain——规则扇出直投用，D-E6）。 */
        private boolean matchesLabels(Instance instance) {
            return selector.labels().isEmpty()
                    || instance.metadata()
                            .entrySet()
                            .containsAll(selector.labels().entrySet());
        }

        private static ServiceChange toNew(ServiceInstances service) {
            List<InstanceChange> changes = new ArrayList<>(service.instances().size());
            for (Instance instance : service.instances()) {
                changes.add(new InstanceChange(instance, ChangeType.NEW));
            }
            return new ServiceChange(service.key(), changes);
        }
    }
}
