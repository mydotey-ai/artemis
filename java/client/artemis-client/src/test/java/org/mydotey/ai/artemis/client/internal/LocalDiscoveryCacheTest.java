package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/** 单订阅本地视图：增量应用/全量替换/union/零拷贝/checksum 表。 */
class LocalDiscoveryCacheTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();
    private static final ServiceId PAY =
            ServiceId.newBuilder().namespace("default").service("pay-service").build();

    private final LocalDiscoveryCache cache = new LocalDiscoveryCache();

    @Test
    void should_apply_new_update_delete_across_clusters() {
        assertThat(apply("cluster-a", upsert(ORDER, instance(ORDER, "i1", "10.0.0.1"))))
                .isTrue();
        assertThat(apply("cluster-b", upsert(ORDER, instance(ORDER, "i2", "10.0.0.2"))))
                .isTrue();
        assertThat(cache.getInstances(ORDER)).hasSize(2);

        assertThat(apply("cluster-a", upsert(ORDER, instance(ORDER, "i1", "10.0.0.9"))))
                .isTrue();
        assertThat(cache.getInstances(ORDER))
                .filteredOn(i -> i.getInstanceId().equals("i1"))
                .extracting(ServiceInstance::getHost)
                .containsExactly("10.0.0.9");

        assertThat(apply("cluster-a", delete(ORDER, "i1"))).isTrue();
        assertThat(cache.getInstances(ORDER))
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("i2");

        assertThat(apply("cluster-b", delete(ORDER, "i2"))).isTrue();
        assertThat(cache.getInstances(ORDER)).isEmpty();
        assertThat(cache.serviceCount()).isZero();
    }

    @Test
    void should_return_immutable_reference_without_clone() {
        apply("cluster-a", upsert(ORDER, instance(ORDER, "i1", "10.0.0.1")));
        List<ServiceInstance> first = cache.getInstances(ORDER);

        assertThat(cache.getInstances(ORDER)).isSameAs(first);
        assertThatThrownBy(() -> first.add(instance(ORDER, "i2", "10.0.0.2")))
                .isInstanceOf(UnsupportedOperationException.class);

        apply("cluster-a", upsert(ORDER, instance(ORDER, "i2", "10.0.0.2")));
        assertThat(cache.getInstances(ORDER)).isNotSameAs(first);
        assertThat(cache.getInstances(ORDER)).hasSize(2);
    }

    @Test
    void should_not_change_when_upsert_is_idempotent() {
        ServiceInstance instance = instance(ORDER, "i1", "10.0.0.1");
        assertThat(apply("cluster-a", upsert(ORDER, instance))).isTrue();
        assertThat(apply("cluster-a", upsert(ORDER, instance))).isFalse();
        assertThat(apply("cluster-a", delete(PAY, "absent"))).isFalse();
    }

    @Test
    void should_replace_all_and_drop_missing_services() {
        apply("cluster-a", upsert(ORDER, instance(ORDER, "i1", "10.0.0.1")));
        apply("cluster-a", upsert(PAY, instance(PAY, "p1", "10.0.0.2")));

        List<ServiceId> changed = cache.replaceAll(Collections.singletonList(new LocalDiscoveryCache.ClusterEntry(
                "cluster-a", ORDER, Collections.singletonList(instance(ORDER, "i1", "10.0.0.1")))));

        assertThat(changed).containsExactly(PAY); // ORDER 同内容不变；响应外 PAY 被删除并通知
        assertThat(cache.getInstances(PAY)).isEmpty();
        assertThat(cache.getInstances(ORDER)).hasSize(1);
    }

    @Test
    void should_update_cluster_attribution_when_union_unchanged() {
        ServiceInstance instance = instance(ORDER, "i1", "10.0.0.1");
        apply("cluster-a", upsert(ORDER, instance));

        List<ServiceId> changed = cache.replaceAll(Collections.singletonList(
                new LocalDiscoveryCache.ClusterEntry("cluster-b", ORDER, Collections.singletonList(instance))));

        assertThat(changed).containsExactly(ORDER); // union 相同但归属变化 → 写回（checksum 收敛）
        assertThat(cache.checksums()).containsOnlyKeys("cluster-b");
        assertThat(cache.checksums().get("cluster-b").get(ORDER))
                .isEqualTo(DiscoveryChecksum.of(Collections.singletonList(instance)));
    }

    @Test
    void should_expose_per_cluster_checksums_matching_canonical_rule() {
        ServiceInstance i1 = instance(ORDER, "i1", "10.0.0.1");
        ServiceInstance i2 = instance(ORDER, "i2", "10.0.0.2");
        apply("cluster-a", upsert(ORDER, i1));
        apply("cluster-a", upsert(ORDER, i2));

        assertThat(cache.checksums().get("cluster-a").get(ORDER))
                .isEqualTo(DiscoveryChecksum.of(Arrays.asList(i1, i2)));
    }

    private boolean apply(String cluster, InstanceChange change) {
        return cache.applyChange(cluster, new ServiceChange(change.service, Collections.singletonList(change)));
    }

    private static InstanceChange upsert(ServiceId service, ServiceInstance instance) {
        return InstanceChange.upsert(instance, InstanceChange.Type.NEW);
    }

    private static InstanceChange delete(ServiceId service, String instanceId) {
        return InstanceChange.delete(service, instanceId);
    }

    private static ServiceInstance instance(ServiceId service, String instanceId, String host) {
        return ServiceInstance.newBuilder()
                .service(service)
                .instanceId(instanceId)
                .host(host)
                .port(8080)
                .weight(100)
                .build();
    }
}
