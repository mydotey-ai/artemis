package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.domain.GroupFilter;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleKey;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleListener;
import org.mydotey.ai.artemis.discovery.domain.RouteRuleSource;
import org.mydotey.ai.artemis.discovery.domain.RouteRules;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

/**
 * 路由规则同步引擎（phase-2 缓存，phase-3 接执行点，骨架见
 * {@link ManagementRuleSynchronizer}）：应用 = 表锁内「GroupFilter 整集替换 + 新旧
 * 规则集对称差扇出」成对（ejection FIX-1 同型）。受影响服务 = 新旧规则集 service 键集
 * 并（route-rule 是服务内资源，selector 不跨服务生效——无 ejection 的 selector 全局
 * 波及分支）；退出视图 DELETE、进入视图 NEW，判定与 {@link GroupFilter} 同源。
 */
@Slf4j
public final class RouteRuleSynchronizer extends ManagementRuleSynchronizer {

    private final RouteRuleSource source;
    private final GroupFilter filter;

    public RouteRuleSynchronizer(
            ManagementSyncProperties sync,
            RouteRuleSource source,
            GroupFilter filter,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            ManagementStatus managementStatus) {
        super(sync, managementStatus, registry, subscriptionManager, "artemis-discovery-route-rule-sync");
        this.source = source;
        this.filter = filter;
    }

    /** 当前生效规则集（filter 同源视图；排障与 IT 断言通道）。 */
    public RouteRules rules() {
        return filter.rules();
    }

    @Override
    protected AutoCloseable watch(EventSink sink) {
        return source.watch(new ForwardingListener(sink));
    }

    @Override
    protected void ack(long appliedVersion) {
        source.ack(appliedVersion);
    }

    @Override
    protected void apply(Object event) {
        if (event instanceof SnapshotEvent snapshot) {
            applyAndFanout(RouteRules.of(snapshot.rules()));
            log.info(
                    "route rules snapshot applied, rules={}, version={}",
                    snapshot.rules().size(),
                    snapshot.version());
            applied(snapshot.version());
        } else if (event instanceof UpsertedEvent upserted) {
            applyAndFanout(filter.rules().with(upserted.rule()));
            applied(upserted.version());
        } else if (event instanceof DeletedEvent deleted) {
            applyAndFanout(filter.rules().without(deleted.key()));
            applied(deleted.version());
        }
    }

    private void applyAndFanout(RouteRules newRules) {
        RouteRules oldRules = filter.rules();
        if (oldRules == newRules) {
            return; // 幂等增量无变化
        }
        subscriptionManager.runWithFanout(() -> {
            filter.replace(newRules);
            Set<ServiceKey> affected = new HashSet<>(oldRules.services());
            affected.addAll(newRules.services());
            fanoutVisibilityDiff(
                    affected,
                    (service, instance) -> oldRules.isVisible(service, instance.metadata()),
                    (service, instance) -> newRules.isVisible(service, instance.metadata()));
        });
    }

    /** gRPC 回调线程转发：只 offer 到本轮队列，慢消费（队列满）触发断流重连。 */
    private final class ForwardingListener implements RouteRuleListener {

        private final EventSink sink;

        ForwardingListener(EventSink sink) {
            this.sink = sink;
        }

        @Override
        public void onSnapshot(List<RouteRule> rules, long version) {
            if (!sink.offer(new SnapshotEvent(rules, version))) {
                log.warn("route rule event queue full, reconnecting for full snapshot");
                sink.signalReconnect();
            }
        }

        @Override
        public void onUpserted(RouteRule rule, long version) {
            if (!sink.offer(new UpsertedEvent(rule, version))) {
                sink.signalReconnect();
            }
        }

        @Override
        public void onDeleted(RouteRuleKey key, long version) {
            if (!sink.offer(new DeletedEvent(key, version))) {
                sink.signalReconnect();
            }
        }

        @Override
        public void onClosed() {
            sink.signalReconnect();
        }
    }

    private record SnapshotEvent(List<RouteRule> rules, long version) {}

    private record UpsertedEvent(RouteRule rule, long version) {}

    private record DeletedEvent(RouteRuleKey key, long version) {}
}
