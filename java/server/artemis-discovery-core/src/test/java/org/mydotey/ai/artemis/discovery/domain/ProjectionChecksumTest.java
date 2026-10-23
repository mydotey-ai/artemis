package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionChecksumTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order");

    @Test
    void should_compute_identical_checksum_when_instance_or_metadata_order_varies() {
        Map<String, String> metadataOne = new LinkedHashMap<>();
        metadataOne.put("b", "2");
        metadataOne.put("a", "1");
        Map<String, String> metadataTwo = new LinkedHashMap<>();
        metadataTwo.put("a", "1");
        metadataTwo.put("b", "2");

        Instance i1 = new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, metadataOne);
        Instance i1SwappedMetadata = new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, metadataTwo);
        assertThat(ProjectionChecksum.of(i1)).isEqualTo(ProjectionChecksum.of(i1SwappedMetadata));

        Instance i2 = new Instance("i2", "10.0.0.2", 9090, InstanceStatus.DOWN, 50, Map.of());
        ServiceInstances forward = new ServiceInstances(ORDER, List.of(i1, i2));
        ServiceInstances reversed = new ServiceInstances(ORDER, List.of(i2, i1));
        assertThat(ProjectionChecksum.of(forward))
                .as("实例集顺序无关（XOR 结合，D-F2）")
                .isEqualTo(ProjectionChecksum.of(reversed));
    }

    @Test
    void should_change_checksum_when_any_instance_field_changes() {
        Instance base = new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("k", "v"));
        int expected = ProjectionChecksum.of(base);

        assertThat(ProjectionChecksum.of(
                        new Instance("i9", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("k", "v"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.9", 8080, InstanceStatus.UP, 100, Map.of("k", "v"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.1", 9090, InstanceStatus.UP, 100, Map.of("k", "v"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.1", 8080, InstanceStatus.DOWN, 100, Map.of("k", "v"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 200, Map.of("k", "v"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("k", "w"))))
                .isNotEqualTo(expected);
        assertThat(ProjectionChecksum.of(
                        new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("j", "v"))))
                .isNotEqualTo(expected);
    }

    @Test
    void should_return_zero_for_empty_instance_set() {
        assertThat(ProjectionChecksum.of(new ServiceInstances(ORDER, List.of())))
                .isZero();
    }
}
