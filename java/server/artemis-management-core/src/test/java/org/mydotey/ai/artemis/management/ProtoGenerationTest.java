package org.mydotey.ai.artemis.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;

class ProtoGenerationTest {

    @Test
    void should_share_management_proto_when_building_management_core() {
        // management.v1 统一生成于 artemis-common（含 registry 只读查询 service），management-core 经依赖共享
        GetServiceInstancesRequest request = GetServiceInstancesRequest.newBuilder()
                .setKey(ServiceKey.newBuilder().setNamespace("default").setService("order-service"))
                .build();

        assertThat(request.getKey().getNamespace()).isEqualTo("default");
    }
}
