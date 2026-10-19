package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseDetail;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStats;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;

/**
 * management.v1 ↔ 领域模型转换（client 方向）：字段级映射手写（映射量小，不引入 MapStruct）。
 * 枚举语义：UNSPECIFIED → UP；未知值 → UNKNOWN。
 */
public final class ManagementProtoMapper {

    public static final ManagementProtoMapper INSTANCE = new ManagementProtoMapper();

    private ManagementProtoMapper() {}

    public ServiceKey toDomain(org.mydotey.ai.artemis.proto.common.v1.ServiceKey key) {
        return new ServiceKey(key.getNamespace(), key.getService());
    }

    public org.mydotey.ai.artemis.proto.common.v1.ServiceKey toProto(ServiceKey key) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace(key.namespace() == null ? "" : key.namespace())
                .setService(key.service() == null ? "" : key.service())
                .build();
    }

    public Instance toDomain(org.mydotey.ai.artemis.proto.common.v1.Instance instance) {
        return new Instance(
                instance.getInstanceId(),
                instance.getHost(),
                instance.getPort(),
                toDomain(instance.getStatus()),
                instance.getWeight(),
                instance.getMetadataMap());
    }

    public ServiceSummary toDomain(org.mydotey.ai.artemis.proto.management.v1.ServiceSummary summary) {
        return new ServiceSummary(toDomain(summary.getKey()), summary.getInstanceCount());
    }

    public LeaseStatus toDomain(org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponseData data) {
        List<LeaseDetail> leases =
                data.getLeasesList().stream().map(this::toDomain).toList();
        var protection = data.getProtection();
        return new LeaseStatus(
                new SelfProtectionStats(
                        protection.getWindowRenewals(), protection.getMaxCount(), protection.getProtecting()),
                leases,
                data.getTruncated());
    }

    public ConfigProperty toDomain(org.mydotey.ai.artemis.proto.management.v1.ConfigProperty property) {
        return new ConfigProperty(
                property.getKey(),
                property.getValue(),
                property.getSource(),
                property.getIsStatic(),
                property.getDefaultValue(),
                property.getDoc());
    }

    private LeaseDetail toDomain(org.mydotey.ai.artemis.proto.management.v1.LeaseDetail lease) {
        return new LeaseDetail(
                toDomain(lease.getService()), lease.getInstanceId(), lease.getAgeMs(), lease.getRemainingTtlMs());
    }

    private static InstanceStatus toDomain(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus status) {
        return switch (status) {
            case INSTANCE_STATUS_UNSPECIFIED, INSTANCE_STATUS_UP -> InstanceStatus.UP;
            case INSTANCE_STATUS_DOWN -> InstanceStatus.DOWN;
            default -> InstanceStatus.UNKNOWN;
        };
    }
}
