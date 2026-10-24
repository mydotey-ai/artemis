package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.ServiceId;

/** 选择器值对象：不可变/防御性拷贝/fail-fast/指纹稳定。 */
class DiscoverySelectorTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order").build();

    @Test
    void should_build_immutable_selector_and_reject_invalid() {
        Set<ServiceId> services = new LinkedHashSet<>();
        services.add(ORDER);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("group", "a");
        Set<String> clusters = new LinkedHashSet<>();
        clusters.add("cluster-a");

        DiscoverySelector selector = DiscoverySelector.newBuilder()
                .services(services)
                .labels(labels)
                .clusters(clusters)
                .build();
        services.add(ServiceId.newBuilder().namespace("x").service("y").build());
        labels.put("zone", "z");
        clusters.add("cluster-b");

        assertThat(selector.services()).containsExactly(ORDER);
        assertThat(selector.labels()).containsEntry("group", "a").doesNotContainKey("zone");
        assertThat(selector.clusters()).containsExactly("cluster-a");
        assertThatThrownBy(() -> selector.services().add(ORDER)).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> DiscoverySelector.newBuilder().putLabel(" ", "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscoverySelector.newBuilder().putLabel("k", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscoverySelector.newBuilder().addCluster(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_share_fingerprint_for_same_content_and_differ_otherwise() {
        DiscoverySelector first = DiscoverySelector.newBuilder()
                .addService(ORDER)
                .putLabel("group", "a")
                .addCluster("cluster-a")
                .build();
        DiscoverySelector same = DiscoverySelector.newBuilder()
                .addService(ORDER)
                .putLabel("group", "a")
                .addCluster("cluster-a")
                .build();
        DiscoverySelector different =
                DiscoverySelector.newBuilder().addService(ORDER).build();

        assertThat(first.fingerprint()).isEqualTo(same.fingerprint());
        assertThat(first).isEqualTo(same);
        assertThat(first.fingerprint()).isNotEqualTo(different.fingerprint());
        assertThat(first.fingerprint()).hasSize(16); // FNV-1a-64
    }

    @Test
    void should_be_order_independent_and_unambiguous_when_fingerprinting() {
        DiscoverySelector ordered = DiscoverySelector.newBuilder()
                .addService(ORDER)
                .addService(
                        ServiceId.newBuilder().namespace("ns2").service("svc2").build())
                .putLabel("a", "b=c")
                .putLabel("x", "y")
                .addCluster("c1")
                .addCluster("c2")
                .build();
        DiscoverySelector reordered = DiscoverySelector.newBuilder()
                .addService(
                        ServiceId.newBuilder().namespace("ns2").service("svc2").build())
                .addService(ORDER)
                .putLabel("x", "y")
                .putLabel("a", "b=c")
                .addCluster("c2")
                .addCluster("c1")
                .build();
        DiscoverySelector ambiguous = DiscoverySelector.newBuilder()
                .addService(ORDER)
                .addService(
                        ServiceId.newBuilder().namespace("ns2").service("svc2").build())
                .putLabel("a=b", "c")
                .putLabel("x", "y")
                .addCluster("c1")
                .addCluster("c2")
                .build();

        assertThat(ordered).isEqualTo(reordered);
        assertThat(ordered.fingerprint()).isEqualTo(reordered.fingerprint()); // 集合序不影响
        assertThat(ordered.fingerprint()).isNotEqualTo(ambiguous.fingerprint()); // key/value 边界无歧义
    }

    @Test
    void should_use_empty_collections_for_all_services() {
        DiscoverySelector all = DiscoverySelector.newBuilder().build();
        assertThat(all.services()).isEmpty();
        assertThat(all.labels()).isEmpty();
        assertThat(all.clusters()).isEmpty();
    }
}
