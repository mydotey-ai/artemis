package org.mydotey.ai.artemis.discovery.infrastructure;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.domain.EjectionFilter;
import org.mydotey.ai.artemis.discovery.domain.EjectionRule;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleListener;
import org.mydotey.ai.artemis.discovery.domain.EjectionRuleSource;
import org.mydotey.ai.artemis.discovery.domain.EjectionRules;
import org.mydotey.ai.artemis.discovery.domain.EjectionTarget;
import org.mydotey.ai.artemis.discovery.domain.ManagementStatus;
import org.mydotey.ai.artemis.discovery.domain.ProjectionRegistry;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionManager;

/**
 * 摘除规则同步引擎（D-E3/D-E6/D-E7，骨架见 {@link ManagementRuleSynchronizer}）：
 * 应用 = 表锁内「EjectionFilter 替换 + 新旧规则集对称差扇出」成对（review FIX-1 同型，
 * 与投影线程「translate + apply」互斥——防双路径事件丢失）。
 *
 * <p>受影响服务 = instance 级规则服务键 ∪ selector 级值集变化时的全部投影服务
 * （selector 规则全局生效，D-E5）；逐投影实例按四级判定取新旧可见性差。
 */
@Slf4j
public final class EjectionRuleSynchronizer extends ManagementRuleSynchronizer {

    private final EjectionRuleSource source;
    private final EjectionFilter filter;

    public EjectionRuleSynchronizer(
            ManagementSyncProperties sync,
            EjectionRuleSource source,
            EjectionFilter filter,
            ProjectionRegistry registry,
            SubscriptionManager subscriptionManager,
            ManagementStatus managementStatus) {
        super(sync, managementStatus, registry, subscriptionManager, "artemis-discovery-ejection-sync");
        this.source = source;
        this.filter = filter;
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
            applyAndFanout(EjectionRules.of(snapshot.rules()));
            log.info(
                    "ejection rules snapshot applied, rules={}, version={}",
                    snapshot.rules().size(),
                    snapshot.version());
            applied(snapshot.version());
        } else if (event instanceof EjectedEvent ejected) {
            applyAndFanout(filter.rules().with(ejected.rule()));
            applied(ejected.version());
        } else if (event instanceof RecoveredEvent recovered) {
            applyAndFanout(filter.rules().without(recovered.target()));
            applied(recovered.version());
        }
    }

    private void applyAndFanout(EjectionRules newRules) {
        EjectionRules oldRules = filter.rules();
        if (oldRules == newRules) {
            return; // 幂等增量无变化
        }
        // 表锁内「规则替换 + 扇出」成对（review FIX-1 同型）
        subscriptionManager.runWithFanout(() -> {
            filter.replace(newRules);
            fanoutDiff(oldRules, newRules);
        });
    }

    private void fanoutDiff(EjectionRules oldRules, EjectionRules newRules) {
        Set<ServiceKey> affected = new HashSet<>(oldRules.services());
        affected.addAll(newRules.services());
        if (!oldRules.selectors().equals(newRules.selectors())) {
            for (String cluster : registry.clusterIds()) {
                for (ServiceInstances serviceInstances : registry.services(cluster)) {
                    affected.add(serviceInstances.key());
                }
            }
        }
        fanoutVisibilityDiff(
                affected,
                (service, instance) -> !oldRules.isEjected(service, instance.instanceId(), instance.metadata()),
                (service, instance) -> !newRules.isEjected(service, instance.instanceId(), instance.metadata()));
    }

    /** gRPC 回调线程转发：只 offer 到本轮队列，慢消费（队列满）触发断流重连。 */
    private final class ForwardingListener implements EjectionRuleListener {

        private final EventSink sink;

        ForwardingListener(EventSink sink) {
            this.sink = sink;
        }

        @Override
        public void onSnapshot(List<EjectionRule> rules, long version) {
            if (!sink.offer(new SnapshotEvent(rules, version))) {
                log.warn("ejection rule event queue full, reconnecting for full snapshot");
                sink.signalReconnect();
            }
        }

        @Override
        public void onEjected(EjectionRule rule, long version) {
            if (!sink.offer(new EjectedEvent(rule, version))) {
                sink.signalReconnect();
            }
        }

        @Override
        public void onRecovered(EjectionTarget target, long version) {
            if (!sink.offer(new RecoveredEvent(target, version))) {
                sink.signalReconnect();
            }
        }

        @Override
        public void onClosed() {
            sink.signalReconnect();
        }
    }

    private record SnapshotEvent(List<EjectionRule> rules, long version) {}

    private record EjectedEvent(EjectionRule rule, long version) {}

    private record RecoveredEvent(EjectionTarget target, long version) {}
}
