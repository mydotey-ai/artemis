package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 订阅事件映射：未知变更类型前向兼容（不构造可能非法的完整实例）。 */
class ProtoMapperDiscoveryTest {

    @Test
    void should_ignore_unknown_change_type_without_constructing_instance() {
        org.mydotey.ai.artemis.proto.common.v1.ServiceKey key =
                org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                        .setNamespace("default")
                        .setService("order")
                        .build();
        // 只带 instance_id（host 空/port 0）：若按 upsert 构造 ServiceInstance 会抛校验异常
        org.mydotey.ai.artemis.proto.discovery.v1.ServiceChange proto =
                org.mydotey.ai.artemis.proto.discovery.v1.ServiceChange.newBuilder()
                        .setKey(key)
                        .addChanges(org.mydotey.ai.artemis.proto.discovery.v1.InstanceChange.newBuilder()
                                .setInstance(org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                                        .setInstanceId("i1"))
                                .setType(org.mydotey.ai.artemis.proto.discovery.v1.ChangeType.CHANGE_TYPE_UNKNOWN))
                        .build();

        ServiceChange mapped = ProtoMapper.fromProto(proto);

        assertThat(mapped.changes).hasSize(1);
        assertThat(mapped.changes.get(0).type).isEqualTo(InstanceChange.Type.UNKNOWN);
        assertThat(mapped.changes.get(0).instance).isNull();
    }
}
