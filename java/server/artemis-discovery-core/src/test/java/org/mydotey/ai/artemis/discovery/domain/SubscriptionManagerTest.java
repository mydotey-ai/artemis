package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubscriptionManagerTest {

    private static final String CLUSTER = "test-cluster";
    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();
    private final SubscriptionManager manager = new SubscriptionManager(registry);
    private final EjectionFilter ejectionFilter = new EjectionFilter();
    private final SubscriptionManager filteredManager =
            new SubscriptionManager(registry, DiscoveryFilterChain.of(ejectionFilter));

    // ---- 基线（D-S6）----

    @Test
    void should_send_confirmation_then_new_baseline_on_subscribe() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(allServices(), target);

        assertThat(target.events).containsExactly("established", "default/order-service:[i1=NEW]");
    }

    @Test
    void should_baseline_only_matching_explicit_services() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a")), service(PAY, instance("p1", "b"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(ORDER), Map.of()), target);

        assertThat(target.events).containsExactly("established", "default/order-service:[i1=NEW]");
    }

    // ---- 翻译矩阵（D-S3）----

    @Test
    void should_translate_new_by_labels_match() {
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(labels("group", "a"), target);

        // NEW 不匹配 labels：丢弃
        fanout(batch(change(instance("i1", "b"), ChangeType.NEW)));
        assertThat(target.events).containsExactly("established");

        // NEW 匹配：推 NEW
        fanout(batch(change(instance("i2", "a"), ChangeType.NEW)));
        assertThat(target.events).containsExactly("established", "default/order-service:[i2=NEW]");
    }

    @Test
    void should_translate_update_all_four_match_states() {
        registry.replaceAll(
                CLUSTER, List.of(service(ORDER, instance("i1", "a"), instance("i2", "b"), instance("i3", "a"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(labels("group", "a"), target);
        target.events.clear();

        // 四象限一批：i1 双匹配 UPDATE、i2 旧不匹配新匹配 NEW、i3 旧匹配新不匹配 DELETE、
        // i4 双不匹配丢弃
        fanout(batch(
                change(instance("i1", "a"), ChangeType.UPDATE),
                change(instance("i2", "a"), ChangeType.UPDATE),
                change(instance("i3", "b"), ChangeType.UPDATE),
                change(instance("i4", "b"), ChangeType.UPDATE)));

        assertThat(target.events).containsExactly("default/order-service:[i1=UPDATE, i2=NEW, i3=DELETE]");
    }

    @Test
    void should_translate_delete_with_old_value_before_apply() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"), instance("i2", "b"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(labels("group", "a"), target);
        target.events.clear();

        // DELETE 的 change.instance 只带 instanceId：匹配判定用旧实例完整字段；i2 旧值不匹配不推
        fanout(batch(
                change(new Instance("i1", "", 0, null, 0, Map.of()), ChangeType.DELETE),
                change(new Instance("i2", "", 0, null, 0, Map.of()), ChangeType.DELETE)));

        assertThat(target.events).containsExactly("default/order-service:[i1=DELETE]");
    }

    @Test
    void should_forward_changes_in_origin_order() {
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(allServices(), target);
        target.events.clear();

        fanout(batch(change(instance("i1", "a"), ChangeType.NEW), change(instance("i2", "a"), ChangeType.NEW)));

        assertThat(target.events).containsExactly("default/order-service:[i1=NEW, i2=NEW]"); // 批内保持产生序
    }

    // ---- 覆盖 diff 续推（D-S7）----

    @Test
    void should_push_diff_and_keep_stream_on_upstream_replace() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(allServices(), target);
        target.events.clear();

        // 模拟同步线程覆盖路径：旧投影 T0 在手，diff(T0, T1) 表锁内成对执行（旧值取 T0）
        Map<ServiceKey, ServiceInstances> oldByKey = new HashMap<>();
        for (ServiceInstances service : registry.services(CLUSTER)) {
            oldByKey.put(service.key(), service);
        }
        List<ServiceInstances> fresh = List.of(service(PAY, instance("p1", "b")));
        manager.runWithFanout(() -> {
            manager.onChanges(CLUSTER, ProjectionDiff.diff(oldByKey, fresh), oldByKey::get);
            registry.replaceAll(CLUSTER, fresh);
        });

        // 新建按 fresh 序在前、消失在后（ProjectionDiff 确定序）
        assertThat(target.events).containsExactly("default/pay-service:[p1=NEW]", "default/order-service:[i1=DELETE]");
    }

    // ---- 生命周期（D-S8）----

    @Test
    void should_not_push_after_subscription_closed() {
        Subscription subscription = manager.subscribe(allServices(), new RecordingTarget());
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(allServices(), target);

        subscription.close();
        target.events.clear();

        fanout(batch(change(instance("i1", "a"), ChangeType.NEW)));

        assertThat(manager.size()).isOne(); // closed 订阅已注销
        assertThat(target.events).containsExactly("default/order-service:[i1=NEW]"); // 未 close 照常收
    }

    // ---- 多集群（phase-5）----

    @Test
    void should_skip_delivery_for_non_matching_clusters() {
        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of(), Set.of(CLUSTER)), target);
        target.events.clear();

        // 非命中 cluster 的变更不投递
        fanout("cluster-b", batch(change(instance("i1", "a"), ChangeType.NEW)));
        assertThat(target.events).isEmpty();

        // 命中 cluster 照常投递
        fanout(batch(change(instance("i1", "a"), ChangeType.NEW)));
        assertThat(target.events).containsExactly("default/order-service:[i1=NEW]");
    }

    @Test
    void should_baseline_only_matching_clusters() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"))));
        registry.replaceAll("cluster-b", List.of(service(PAY, instance("p1", "b"))));

        RecordingTarget target = new RecordingTarget();
        manager.subscribe(new DiscoverySelector(Set.of(), Map.of(), Set.of(CLUSTER)), target);

        assertThat(target.events).containsExactly("established", "default/order-service:[i1=NEW]");
    }

    // ---- 摘除过滤（phase-6，D-E1/D-E6）----

    @Test
    void should_baseline_only_visible_instances_when_filtered() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"), instance("i2", "a"))));
        ejectionFilter.replace(EjectionRules.of(List.of(new EjectionRule(ORDER, "i1", "test"))));

        RecordingTarget target = new RecordingTarget();
        filteredManager.subscribe(allServices(), target);

        assertThat(target.events).containsExactly("established", "default/order-service:[i2=NEW]");
    }

    @Test
    void should_translate_projection_changes_with_ejection_filter() {
        RecordingTarget target = new RecordingTarget();
        filteredManager.subscribe(allServices(), target);
        target.events.clear();

        ejectionFilter.replace(EjectionRules.of(List.of(new EjectionRule(ORDER, "i2", "test"))));

        // NEW 已摘除：无事件
        filteredManager.onChanges(CLUSTER, List.of(batch(change(instance("i2", "a"), ChangeType.NEW))), key -> null);
        assertThat(target.events).isEmpty();

        // UPDATE 转为摘除（新值不可见、旧值可见）：退出视图 DELETE(previous)
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i2", "a"))));
        ejectionFilter.replace(EjectionRules.EMPTY);
        filteredManager.onChanges(
                CLUSTER,
                List.of(batch(change(instance("i2", "a"), ChangeType.UPDATE))),
                key -> registry.service(CLUSTER, key));
        assertThat(target.events).containsExactly("default/order-service:[i2=UPDATE]");
        target.events.clear();

        ejectionFilter.replace(EjectionRules.of(List.of(new EjectionRule(ORDER, "i2", "test"))));
        // UPDATE 已摘除实例：previous/new 同为摘除态（同一实例、同一规则快照），无视图效果
        filteredManager.onChanges(
                CLUSTER,
                List.of(batch(change(instance("i2", "a"), ChangeType.UPDATE))),
                key -> registry.service(CLUSTER, key));
        assertThat(target.events).isEmpty();

        // DELETE 已摘除实例：无事件（不在视图，删除无效果）
        target.events.clear();
        filteredManager.onChanges(
                CLUSTER,
                List.of(batch(change(new Instance("i2", "", 0, null, 0, Map.of()), ChangeType.DELETE))),
                key -> registry.service(CLUSTER, key));
        assertThat(target.events).isEmpty();
    }

    @Test
    void should_deliver_view_changes_on_rules_change() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", "a"), instance("i2", "b"))));
        registry.replaceAll("cluster-b", List.of(service(ORDER, instance("i1", "a"))));

        RecordingTarget all = new RecordingTarget();
        RecordingTarget grouped = new RecordingTarget();
        RecordingTarget clusterOnly = new RecordingTarget();
        filteredManager.subscribe(allServices(), all);
        filteredManager.subscribe(labels("group", "a"), grouped);
        filteredManager.subscribe(new DiscoverySelector(Set.of(), Map.of(), Set.of("cluster-b")), clusterOnly);
        all.events.clear();
        grouped.events.clear();
        clusterOnly.events.clear();

        // 视图语义直投（D-E6）：eject i1 → DELETE、recover i2 → NEW；labels/cluster 过滤照做
        filteredManager.runWithFanout(() -> filteredManager.onViewChanges(
                CLUSTER,
                List.of(
                        batch(change(instance("i1", "a"), ChangeType.DELETE)),
                        batch(change(instance("i2", "b"), ChangeType.NEW)))));

        assertThat(all.events).containsExactly("default/order-service:[i1=DELETE]", "default/order-service:[i2=NEW]");
        // labels: i1 匹配 group=a 收 DELETE、i2 group=b 不匹配不推
        assertThat(grouped.events).containsExactly("default/order-service:[i1=DELETE]");
        // cluster: cluster-b 订阅者收本 cluster 的视图变更（同一 service 逐 cluster 独立投递）
        assertThat(clusterOnly.events).isEmpty();

        filteredManager.runWithFanout(() -> filteredManager.onViewChanges(
                "cluster-b", List.of(batch(change(instance("i1", "a"), ChangeType.DELETE)))));
        assertThat(clusterOnly.events).containsExactly("default/order-service:[i1=DELETE]");
    }

    // ---- 设施 ----

    private void fanout(ServiceChange change) {
        fanout(CLUSTER, change);
    }

    private void fanout(String cluster, ServiceChange change) {
        manager.onChanges(cluster, List.of(change), key -> registry.service(cluster, key));
    }

    private static InstanceChange change(Instance instance, ChangeType type) {
        return new InstanceChange(instance, type);
    }

    private static ServiceChange batch(InstanceChange... changes) {
        return new ServiceChange(ORDER, List.of(changes));
    }

    private static DiscoverySelector allServices() {
        return new DiscoverySelector(Set.of(), Map.of());
    }

    private static DiscoverySelector labels(String key, String value) {
        return new DiscoverySelector(Set.of(), Map.of(key, value));
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
    }

    private static Instance instance(String instanceId, String group) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("group", group));
    }

    /** 收集推送为可断言字符串（单 service 批逐 change 记录 id=TYPE）。 */
    private static final class RecordingTarget implements PushTarget {

        final List<String> events = new ArrayList<>();

        @Override
        public void established() {
            events.add("established");
        }

        @Override
        public void accept(String cluster, ServiceChange change) {
            List<String> parts = new ArrayList<>();
            for (InstanceChange c : change.changes()) {
                parts.add(c.instance().instanceId() + "=" + c.type());
            }
            events.add(change.key().namespace() + "/" + change.key().service() + ":" + parts);
        }
    }
}
