package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.discovery.application.DiscoveryQueryService;

class ProjectionRegistryTest {

    private static final String CLUSTER = "cluster-a";

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey USER = new ServiceKey("default", "user-service");
    private static final ServiceKey OTHER_NS = new ServiceKey("other", "order-service");

    private final ProjectionRegistry registry = new ProjectionRegistry();

    private static Instance instance(String id) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }

    private static Instance instance(String id, Map<String, String> metadata) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, metadata);
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
    }

    private static List<ServiceChange> change(ServiceKey key, InstanceChange... changes) {
        return List.of(new ServiceChange(key, List.of(changes)));
    }

    @Test
    void should_cover_all_services_and_drop_missing_when_replace_all() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"), instance("i2"))));
        registry.replaceAll(CLUSTER, List.of(service(USER, instance("u1")))); // ORDER 消失

        assertThat(registry.query(new DiscoverySelector(java.util.Set.of(), Map.of())))
                .extracting(ClusterServiceInstances::instances)
                .extracting(s -> s.key())
                .containsExactly(USER);
        // 重复覆盖幂等
        registry.replaceAll(CLUSTER, List.of(service(USER, instance("u1"))));
        assertThat(registry.query(new DiscoverySelector(java.util.Set.of(), Map.of())))
                .hasSize(1);
    }

    @Test
    void should_apply_new_update_delete_changes() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"))));

        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i2"), ChangeType.NEW)));
        registry.apply(
                CLUSTER,
                change(
                        ORDER,
                        new InstanceChange(
                                new Instance("i1", "10.0.0.9", 8080, InstanceStatus.DOWN, 100, Map.of()),
                                ChangeType.UPDATE)));
        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i2"), ChangeType.DELETE)));

        List<Instance> instances = find(ORDER).instances().instances();
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).host()).isEqualTo("10.0.0.9");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.DOWN);

        // 未知服务 DELETE 忽略
        registry.apply(CLUSTER, change(USER, new InstanceChange(instance("u9"), ChangeType.DELETE)));
        assertThat(find(USER)).isNull();
    }

    @Test
    void should_remove_service_entry_when_last_instance_deleted() {
        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i1"), ChangeType.NEW)));
        assertThat(find(ORDER)).isNotNull();

        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i1"), ChangeType.DELETE)));
        assertThat(find(ORDER)).as("空即删条目（D-D2）").isNull();
        assertThat(registry.clusterIds()).contains(CLUSTER); // cluster 结构保留，仅条目删除
    }

    @Test
    void should_keep_siblings_when_delete_unknown_instance_id() {
        registry.apply(
                CLUSTER,
                change(
                        ORDER,
                        new InstanceChange(instance("i1"), ChangeType.NEW),
                        new InstanceChange(instance("i2"), ChangeType.NEW)));

        // 陈旧/重复 DELETE（instanceId 不在实例集中）：no-op，不误删服务条目与兄弟实例
        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i9"), ChangeType.DELETE)));

        assertThat(find(ORDER)).isNotNull();
        assertThat(find(ORDER).instances().instances()).hasSize(2);
    }

    @Test
    void should_query_by_services_and_labels() {
        registry.replaceAll(
                CLUSTER,
                List.of(
                        service(ORDER, instance("i1", Map.of("group", "a")), instance("i2", Map.of("group", "b"))),
                        service(USER, instance("u1", Map.of("group", "a"))),
                        service(OTHER_NS, instance("x1"))));

        // 空 services = 全部（三个不同 key）
        QueryResponse all = query(java.util.Set.of(), Map.of());
        assertThat(all.services()).hasSize(3);

        // 精确集合
        QueryResponse byKey = query(java.util.Set.of(ORDER, USER), Map.of());
        assertThat(byKey.services()).extracting(s -> s.instances().key()).containsExactlyInAnyOrder(ORDER, USER);

        // labels 实例级过滤（实例子集）
        QueryResponse byLabel = query(java.util.Set.of(), Map.of("group", "a"));
        assertThat(byLabel.services())
                .allSatisfy(s -> s.instances().instances().stream()
                        .allMatch(i -> "a".equals(i.metadata().get("group"))));
    }

    @Test
    void should_return_empty_entry_only_when_labels_filter_out_all_instances() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1", Map.of("group", "a")))));

        // 显式命中 + labels 全不匹配 → 返回空实例条目（有效答案）
        QueryResponse explicit = query(java.util.Set.of(ORDER), Map.of("group", "z"));
        assertThat(explicit.services()).hasSize(1);
        assertThat(explicit.services().get(0).instances().instances()).isEmpty();

        // 全量查询不返回过滤后空条目
        assertThat(query(java.util.Set.of(), Map.of("group", "z")).services()).isEmpty();

        // 服务零实例（条目已删）与未命中同形：不返回条目
        registry.apply(
                CLUSTER, change(ORDER, new InstanceChange(instance("i1", Map.of("group", "a")), ChangeType.DELETE)));
        assertThat(query(java.util.Set.of(ORDER), Map.of()).services()).isEmpty();
    }

    @Test
    void should_reject_blank_service_key_with_invalid_argument() {
        DiscoveryQueryService queryService = new DiscoveryQueryService(registry, new UpstreamStatuses());
        assertThatThrownBy(() -> queryService.query(new QueryRequest(List.of(new ServiceKey("", "svc")), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryService.query(new QueryRequest(List.of(new ServiceKey("ns", " ")), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_blank_cluster_with_invalid_argument() {
        DiscoveryQueryService queryService = new DiscoveryQueryService(registry, new UpstreamStatuses());
        assertThatThrownBy(() -> queryService.query(new QueryRequest(List.of(), Map.of(), List.of(" "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 多集群（phase-5）----

    @Test
    void should_filter_query_by_clusters() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"))));
        registry.replaceAll("cluster-b", List.of(service(ORDER, instance("i2")), service(USER, instance("u1"))));

        // 空 = union：同 ServiceKey 双 cluster 并列 + 各自独有服务
        QueryResponse union = query(java.util.Set.of(), Map.of(), java.util.Set.of());
        assertThat(union.services()).hasSize(3);
        assertThat(union.services())
                .extracting(ClusterServiceInstances::cluster)
                .containsExactlyInAnyOrder(CLUSTER, "cluster-b", "cluster-b");

        // 精确命中：只返回命中 cluster 的条目
        QueryResponse byCluster = query(java.util.Set.of(), Map.of(), java.util.Set.of(CLUSTER));
        assertThat(byCluster.services()).hasSize(1);
        assertThat(byCluster.services().get(0).cluster()).isEqualTo(CLUSTER);
        assertThat(byCluster.services().get(0).instances().key()).isEqualTo(ORDER);

        // 未知 cluster 名静默无命中
        assertThat(query(java.util.Set.of(), Map.of(), java.util.Set.of("cluster-x"))
                        .services())
                .isEmpty();
    }

    @Test
    void should_filter_checksums_by_clusters() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"))));
        registry.replaceAll("cluster-b", List.of(service(USER, instance("u1"))));

        // 空 = union：每 cluster 一组
        assertThat(registry.checksums(new DiscoverySelector(java.util.Set.of(), Map.of())))
                .extracting(ClusterChecksums::cluster)
                .containsExactlyInAnyOrder(CLUSTER, "cluster-b");

        // 精确命中 + 未命中空表
        List<ClusterChecksums> hit =
                registry.checksums(new DiscoverySelector(java.util.Set.of(), Map.of(), java.util.Set.of(CLUSTER)));
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).cluster()).isEqualTo(CLUSTER);
        assertThat(registry.checksums(
                        new DiscoverySelector(java.util.Set.of(), Map.of(), java.util.Set.of("cluster-x"))))
                .isEmpty();
    }

    @Test
    void should_maintain_cached_checksum_on_apply_and_replace_all() {
        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i1"), ChangeType.NEW)));
        assertThat(checksum(ORDER)).isEqualTo(ProjectionChecksum.of(find(ORDER).instances()));

        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i2"), ChangeType.NEW)));
        assertThat(checksum(ORDER))
                .as("条目变更后缓存随新条目重算（D-F3）")
                .isEqualTo(ProjectionChecksum.of(find(ORDER).instances()));

        registry.apply(CLUSTER, change(ORDER, new InstanceChange(instance("i1"), ChangeType.DELETE)));
        assertThat(checksum(ORDER)).isEqualTo(ProjectionChecksum.of(find(ORDER).instances()));

        // 覆盖：ORDER 消失（缺项）、USER 新建
        registry.replaceAll(CLUSTER, List.of(service(USER, instance("u1"))));
        assertThat(checksum(ORDER)).as("空即删条目，对账表缺项（D-F1）").isNull();
        assertThat(checksum(USER)).isEqualTo(ProjectionChecksum.of(find(USER).instances()));
    }

    @Test
    void should_omit_and_compute_view_checksum_by_labels_selector() {
        registry.replaceAll(
                CLUSTER,
                List.of(
                        service(ORDER, instance("i1", Map.of("group", "a")), instance("i2", Map.of("group", "b"))),
                        service(USER, instance("u1", Map.of("group", "a")))));

        // labels 视图现算（D-F3）：checksum = 过滤视图 [i1] 的指纹，条目按视图有实例收录
        // （code-review FIX-3：CHM 弱一致迭代序不定，按 key 定位断言、不依赖顺序）
        List<ServiceChecksum> view = registry.checksums(new DiscoverySelector(java.util.Set.of(), Map.of("group", "a")))
                .get(0)
                .services();
        assertThat(view).extracting(ServiceChecksum::key).containsExactlyInAnyOrder(ORDER, USER);
        assertThat(view.stream()
                        .filter(entry -> entry.key().equals(ORDER))
                        .findFirst()
                        .orElseThrow()
                        .checksum())
                .isEqualTo(ProjectionChecksum.of(service(ORDER, instance("i1", Map.of("group", "a")))));

        // 滤空缺项：全滤空 → 无条目（D-F1）
        assertThat(registry.checksums(new DiscoverySelector(java.util.Set.of(), Map.of("group", "z"))))
                .isEmpty();

        // 显式集合命中（无 labels 读缓存）：缺失服务不返回
        List<ServiceChecksum> hit = registry.checksums(
                        new DiscoverySelector(java.util.Set.of(ORDER, OTHER_NS), Map.of()))
                .get(0)
                .services();
        assertThat(hit).extracting(ServiceChecksum::key).containsExactly(ORDER);
        assertThat(hit.get(0).checksum())
                .isEqualTo(ProjectionChecksum.of(find(ORDER).instances()));
    }

    private ClusterServiceInstances find(ServiceKey key) {
        return registry.query(new DiscoverySelector(java.util.Set.of(key), Map.of())).stream()
                .filter(s -> s.instances().key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private Integer checksum(ServiceKey key) {
        return registry.checksums(new DiscoverySelector(java.util.Set.of(), Map.of())).stream()
                .flatMap(cluster -> cluster.services().stream())
                .filter(service -> service.key().equals(key))
                .map(ServiceChecksum::checksum)
                .findFirst()
                .orElse(null);
    }

    private QueryResponse query(java.util.Set<ServiceKey> services, Map<String, String> labels) {
        return query(services, labels, java.util.Set.of());
    }

    private QueryResponse query(
            java.util.Set<ServiceKey> services, Map<String, String> labels, java.util.Set<String> clusters) {
        return new DiscoveryQueryService(registry, new UpstreamStatuses())
                .query(new QueryRequest(List.copyOf(services), labels, List.copyOf(clusters)));
    }

    // ---- 摘除过滤（phase-6，D-E1/D-E5/D-E8）----

    @Test
    void should_filter_query_and_checksums_with_chain() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"), instance("i2"))));
        EjectionFilter filter = new EjectionFilter();
        filter.replace(EjectionRules.of(List.of(EjectionRule.instance(ORDER, "i1", "test"))));
        DiscoveryFilterChain chain = DiscoveryFilterChain.of(filter);
        DiscoverySelector all = new DiscoverySelector(java.util.Set.of(), Map.of());

        // 全量查询：过滤后仍有实例 → 返回过滤视图
        List<ClusterServiceInstances> queried = registry.query(all, chain);
        assertThat(queried).hasSize(1);
        assertThat(queried.get(0).instances().instances())
                .extracting(Instance::instanceId)
                .containsExactly("i2");

        // 显式命中：过滤后仍有实例 → 返回过滤视图（i1 已摘除、i2 在）
        List<ClusterServiceInstances> explicit =
                registry.query(new DiscoverySelector(java.util.Set.of(ORDER), Map.of()), chain);
        assertThat(explicit).hasSize(1);
        assertThat(explicit.get(0).instances().instances())
                .extracting(Instance::instanceId)
                .containsExactly("i2");

        // checksum 过滤后口径（D-E1）：= 过滤视图 [i2] 的指纹
        List<ServiceChecksum> checksums = registry.checksums(all, chain).stream()
                .flatMap(cluster -> cluster.services().stream())
                .toList();
        assertThat(checksums)
                .containsExactly(new ServiceChecksum(
                        ORDER, ProjectionChecksum.of(new ServiceInstances(ORDER, List.of(instance("i2"))))));

        // 全部实例摘除：显式命中 → 空实例集条目（对齐 labels 语义）；全量查询无条目
        filter.replace(EjectionRules.of(
                List.of(EjectionRule.instance(ORDER, "i1", "test"), EjectionRule.instance(ORDER, "i2", "test"))));
        List<ClusterServiceInstances> emptied =
                registry.query(new DiscoverySelector(java.util.Set.of(ORDER), Map.of()), chain);
        assertThat(emptied).hasSize(1);
        assertThat(emptied.get(0).instances().instances()).isEmpty();
        assertThat(registry.query(all, chain)).isEmpty();
    }

    @Test
    void should_read_cached_checksum_when_chain_has_no_effect() {
        registry.replaceAll(CLUSTER, List.of(service(ORDER, instance("i1"), instance("i2"))));
        EjectionFilter filter = new EjectionFilter(); // 空规则
        DiscoveryFilterChain chain = DiscoveryFilterChain.of(filter);
        DiscoverySelector all = new DiscoverySelector(java.util.Set.of(), Map.of());

        int unfiltered = checksum(ORDER);
        assertThat(unfiltered).isNotZero();

        // 空规则（无过滤效果）→ 原引用 → 读条目缓存（D-E8）
        List<ServiceChecksum> withChain = registry.checksums(all, chain).stream()
                .flatMap(cluster -> cluster.services().stream())
                .filter(service -> service.key().equals(ORDER))
                .toList();
        assertThat(withChain).containsExactly(new ServiceChecksum(ORDER, unfiltered));

        // 摘除不命中任何投影实例 → 仍无效果 → 缓存
        filter.replace(EjectionRules.of(List.of(EjectionRule.instance(USER, "u1", "test"))));
        List<ServiceChecksum> missChain = registry.checksums(all, chain).stream()
                .flatMap(cluster -> cluster.services().stream())
                .filter(service -> service.key().equals(ORDER))
                .toList();
        assertThat(missChain).containsExactly(new ServiceChecksum(ORDER, unfiltered));
    }
}
