package org.mydotey.ai.artemis.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;

class ProtoGenerationTest {

    @Test
    void should_generate_registry_proto_when_building_registry_core() {
        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setSeq(1L)
                .addServices(ServiceInstances.newBuilder()
                        .setKey(ServiceKey.newBuilder().setNamespace("default").setService("order-service"))
                        .build())
                .build();

        assertThat(request.getSeq()).isEqualTo(1L);
        assertThat(request.getServices(0).getKey().getService()).isEqualTo("order-service");

        // grpc stub 生成链路（protoc-gen-grpc-java binary 插件）
        assertThat(RegistryServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("artemis.registry.v1.RegistryService");
    }

    @Test
    void should_generate_replication_proto_when_building_registry_core() {
        org.mydotey.ai.artemis.proto.replication.v1.ClusterMember member =
                org.mydotey.ai.artemis.proto.replication.v1.ClusterMember.newBuilder()
                        .setNodeId("node-a")
                        .setPeerHost("10.0.0.1")
                        .setPeerPort(9093)
                        .build();

        assertThat(member.getNodeId()).isEqualTo("node-a");
        assertThat(org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc.getServiceDescriptor()
                        .getName())
                .isEqualTo("artemis.replication.v1.ReplicationService");
    }
}
