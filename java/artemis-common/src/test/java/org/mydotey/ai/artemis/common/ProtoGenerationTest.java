package org.mydotey.ai.artemis.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;

class ProtoGenerationTest {

    @Test
    void should_generate_common_proto_when_building_artemis_common() {
        ServiceKey key = ServiceKey.newBuilder()
                .setNamespace("default")
                .setService("order-service")
                .build();

        assertThat(key.getNamespace()).isEqualTo("default");
        assertThat(key.getService()).isEqualTo("order-service");

        Instance instance = Instance.newBuilder()
                .setInstanceId("i-1")
                .setHost("10.0.0.1")
                .setPort(8080)
                .build();

        ServiceInstances instances =
                ServiceInstances.newBuilder().setKey(key).addInstances(instance).build();

        assertThat(instances.getInstancesCount()).isEqualTo(1);
        assertThat(instances.getInstances(0).getInstanceId()).isEqualTo("i-1");

        // 契约缺省语义：UNSPECIFIED 同 UP；ack=0 即 ACK_SUCCESS（fail-open 取舍）
        assertThat(Instance.getDefaultInstance().getStatus()).isEqualTo(InstanceStatus.INSTANCE_STATUS_UNSPECIFIED);
        assertThat(ResponseStatus.getDefaultInstance().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(ServiceKey.getDefaultInstance().getNamespace()).isEmpty();
    }
}
