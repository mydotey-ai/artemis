package org.mydotey.ai.artemis.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;

class ProtoReferenceTest {

    @Test
    void should_reference_common_proto_when_building_discovery_core() {
        // common.v1 经 artemis-common Maven 依赖引用（生成矩阵：discovery-core 不自生成 common）
        ServiceKey key = ServiceKey.newBuilder()
                .setNamespace("default")
                .setService("order-service")
                .build();

        Instance instance = Instance.newBuilder().setInstanceId("i-1").build();

        assertThat(key.getService()).isEqualTo("order-service");
        assertThat(instance.getInstanceId()).isEqualTo("i-1");
    }
}
