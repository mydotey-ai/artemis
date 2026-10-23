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
        return new DiscoveryQueryService(registry, new UpstreamStatuses())
                .query(new QueryRequest(List.copyOf(services), labels));
    }
}
