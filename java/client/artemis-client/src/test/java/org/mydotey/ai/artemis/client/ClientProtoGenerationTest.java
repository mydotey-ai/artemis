package org.mydotey.ai.artemis.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;

class ClientProtoGenerationTest {

    @Test
    void should_generate_common_and_registry_proto_when_building_client() {
        // client 自生成组（protoc 3.25.x / Java 8），测试经 toolchains fork JDK 8 JVM 执行
        HeartbeatRequest request = HeartbeatRequest.newBuilder().setSeq(42L).build();

        assertThat(request.getSeq()).isEqualTo(42L);
        assertThat(ServiceKey.getDefaultInstance().getService()).isEmpty();
        assertThat(RegistryServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("artemis.registry.v1.RegistryService");
    }
}
