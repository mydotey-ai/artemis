package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeBatch;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeType;
import org.mydotey.ai.artemis.proto.projection.v1.InstanceChange;
import org.mydotey.ai.artemis.proto.projection.v1.ServiceChange;

/**
 * 投影契约的 dto ↔ 领域模型转换（手写：proto builder 无公开构造器/setter，且枚举常量带前缀、
 * 含消费方语义收敛）。公共消息（ServiceKey / Instance / ServiceInstances）复用
 * {@link RegistryProtoMapper}，不重复定义。
 */
public final class ProjectionProtoMapper {

    private ProjectionProtoMapper() {}

    public static ChangeBatch toProto(List<org.mydotey.ai.artemis.registry.domain.ServiceChange> batch) {
        ChangeBatch.Builder builder = ChangeBatch.newBuilder();
        for (org.mydotey.ai.artemis.registry.domain.ServiceChange service : batch) {
            builder.addServices(toProto(service));
        }
        return builder.build();
    }

    public static ServiceChange toProto(org.mydotey.ai.artemis.registry.domain.ServiceChange service) {
        ServiceChange.Builder builder =
                ServiceChange.newBuilder().setKey(RegistryProtoMapper.INSTANCE.toProto(service.key()));
        for (org.mydotey.ai.artemis.registry.domain.InstanceChange change : service.changes()) {
            builder.addChanges(toProto(change));
        }
        return builder.build();
    }

    public static InstanceChange toProto(org.mydotey.ai.artemis.registry.domain.InstanceChange change) {
        return InstanceChange.newBuilder()
                .setInstance(RegistryProtoMapper.INSTANCE.toProto(change.instance()))
                .setType(toProto(change.type()))
                .build();
    }

    /** registry 内部事件 → 投影三值；EVICTED 防御性映射 DELETE（分发口径已在 dispatcher 收敛）。 */
    public static ChangeType toProto(org.mydotey.ai.artemis.registry.domain.ChangeType type) {
        return switch (type) {
            case NEW -> ChangeType.CHANGE_TYPE_NEW;
            case UPDATE -> ChangeType.CHANGE_TYPE_UPDATE;
            case DELETE, EVICTED -> ChangeType.CHANGE_TYPE_DELETE;
            case RENEW -> throw new IllegalArgumentException("RENEW is not a projection change type");
        };
    }
}
