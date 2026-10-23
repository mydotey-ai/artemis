package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeBatch;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeType;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceChange;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** 投影契约映射：三值枚举 + 最小 DELETE 实例 + 公共模型复用。 */
class ProjectionProtoMapperTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    @Test
    void should_map_three_change_types() {
        assertThat(ProjectionProtoMapper.toProto(org.mydotey.ai.artemis.registry.domain.ChangeType.NEW))
                .isEqualTo(ChangeType.CHANGE_TYPE_NEW);
        assertThat(ProjectionProtoMapper.toProto(org.mydotey.ai.artemis.registry.domain.ChangeType.UPDATE))
                .isEqualTo(ChangeType.CHANGE_TYPE_UPDATE);
        assertThat(ProjectionProtoMapper.toProto(org.mydotey.ai.artemis.registry.domain.ChangeType.DELETE))
                .isEqualTo(ChangeType.CHANGE_TYPE_DELETE);
        // registry 内部清理事件防御性映射 DELETE；RENEW 不应到达投影边界
        assertThat(ProjectionProtoMapper.toProto(org.mydotey.ai.artemis.registry.domain.ChangeType.EVICTED))
                .isEqualTo(ChangeType.CHANGE_TYPE_DELETE);
        assertThatThrownBy(() -> ProjectionProtoMapper.toProto(org.mydotey.ai.artemis.registry.domain.ChangeType.RENEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_map_batch_with_minimal_delete_instance() {
        Instance instance = new Instance("i1", "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("zone", "a"));
        Instance deleted = new Instance("i2", "", 0, InstanceStatus.UNSPECIFIED, 0, Map.of());

        ChangeBatch batch = ProjectionProtoMapper.toProto(List.of(
                new ServiceChange(
                        KEY,
                        List.of(new InstanceChange(
                                KEY, instance, org.mydotey.ai.artemis.registry.domain.ChangeType.UPDATE, 1L))),
                new ServiceChange(
                        KEY,
                        List.of(new InstanceChange(
                                KEY, deleted, org.mydotey.ai.artemis.registry.domain.ChangeType.DELETE, 2L)))));

        assertThat(batch.getServicesList()).hasSize(2).allSatisfy(service -> {
            assertThat(service.getKey().getNamespace()).isEqualTo("default");
            assertThat(service.getKey().getService()).isEqualTo("order-service");
        });
        assertThat(batch.getServices(0).getChanges(0).getInstance().getMetadataMap())
                .containsEntry("zone", "a");
        assertThat(batch.getServices(0).getChanges(0).getType()).isEqualTo(ChangeType.CHANGE_TYPE_UPDATE);
        assertThat(batch.getServices(1).getChanges(0).getInstance().getInstanceId())
                .isEqualTo("i2");
        assertThat(batch.getServices(1).getChanges(0).getType()).isEqualTo(ChangeType.CHANGE_TYPE_DELETE);
    }
}
