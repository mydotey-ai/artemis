package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.InstanceStatus;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/** canonical checksum 规则（api/discovery-reconciliation-api.md §3）跨实现锚点。 */
class DiscoveryChecksumTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order").build();

    @Test
    void should_match_golden_vector_when_computing_checksum() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("group", "a");
        ServiceInstance instance = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .status(InstanceStatus.UP)
                .weight(100)
                .metadata(metadata)
                .build();

        // golden vector 3584519012 = 0xd5a76b64（有符号 int 位模式相同）
        assertThat(DiscoveryChecksum.of(Collections.singletonList(instance))).isEqualTo((int) 3584519012L);
    }

    @Test
    void should_be_order_independent_when_computing_checksum() {
        Map<String, String> metadataA = new LinkedHashMap<>();
        metadataA.put("group", "a");
        metadataA.put("zone", "z1");
        Map<String, String> metadataB = new LinkedHashMap<>();
        metadataB.put("zone", "z1");
        metadataB.put("group", "a");

        ServiceInstance first = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .weight(100)
                .metadata(metadataA)
                .build();
        ServiceInstance sameContent = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .weight(100)
                .metadata(metadataB)
                .build();
        ServiceInstance second = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i2")
                .host("10.0.0.2")
                .port(8080)
                .weight(100)
                .build();

        int left = DiscoveryChecksum.of(java.util.Arrays.asList(first, second));
        int right = DiscoveryChecksum.of(java.util.Arrays.asList(second, sameContent));
        assertThat(left).isEqualTo(right);
        assertThat(DiscoveryChecksum.of(Collections.<ServiceInstance>emptyList()))
                .isZero();
    }

    @Test
    void should_distinguish_status_names_when_computing_checksum() {
        ServiceInstance up = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .status(InstanceStatus.UP)
                .weight(100)
                .build();
        ServiceInstance unspecified = ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i1")
                .host("10.0.0.1")
                .port(8080)
                .status(InstanceStatus.UNSPECIFIED)
                .weight(100)
                .build();

        assertThat(DiscoveryChecksum.of(Collections.singletonList(up)))
                .isNotEqualTo(DiscoveryChecksum.of(Collections.singletonList(unspecified)));
    }
}
