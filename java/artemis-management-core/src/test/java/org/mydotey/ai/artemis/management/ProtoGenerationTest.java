package org.mydotey.ai.artemis.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;

class ProtoGenerationTest {

    @Test
    void should_generate_registry_proto_when_building_management_core() {
        // management-core 生成矩阵含 registry.v1（读注册表视图）：本模块自生成
        RegisterRequest request = RegisterRequest.newBuilder()
                .addServices(ServiceInstances.newBuilder()
                        .setKey(ServiceKey.newBuilder().setNamespace("default").setService("order-service"))
                        .build())
                .build();

        assertThat(request.getServices(0).getKey().getNamespace()).isEqualTo("default");
    }
}
