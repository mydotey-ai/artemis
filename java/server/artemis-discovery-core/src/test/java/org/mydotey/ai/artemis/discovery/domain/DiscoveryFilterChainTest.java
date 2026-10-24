package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryFilterChainTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");

    @Test
    void should_return_original_reference_when_all_visible() {
        List<Instance> instances = List.of(instance("i1"), instance("i2"));

        assertThat(DiscoveryFilterChain.empty().filter(ORDER, instances)).isSameAs(instances);
        assertThat(DiscoveryFilterChain.of((service, instance) -> true).filter(ORDER, instances))
                .isSameAs(instances);
    }

    @Test
    void should_chain_filters_in_order() {
        DiscoveryFilterChain chain = DiscoveryFilterChain.of(
                (service, instance) -> !instance.instanceId().equals("i1"),
                (service, instance) -> !instance.instanceId().equals("i2"));

        assertThat(chain.filter(ORDER, List.of(instance("i1"), instance("i2"), instance("i3"))))
                .extracting(Instance::instanceId)
                .containsExactly("i3");
        assertThat(chain.isVisible(ORDER, instance("i1"))).isFalse();
        assertThat(chain.isVisible(ORDER, instance("i3"))).isTrue();
    }

    @Test
    void should_fail_open_when_filter_throws() {
        DiscoveryFilterChain chain = DiscoveryFilterChain.of(
                (service, instance) -> {
                    throw new IllegalStateException("boom");
                },
                (service, instance) -> false);

        // 第一个 filter 异常放行（fail-open，D-E3），第二个 filter 正常拒绝
        assertThat(chain.isVisible(ORDER, instance("i1"))).isFalse();
        List<Instance> instances = List.of(instance("i1"));
        assertThat(chain.filter(ORDER, instances)).isEmpty();
    }

    private static Instance instance(String id) {
        return new Instance(id, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of());
    }
}
