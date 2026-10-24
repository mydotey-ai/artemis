package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseDetail;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStats;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceProtectionStatus;
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

    // ---- 摘除规则流（management.v1，toProto 出站事件）----

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toSnapshotEvent(List<EjectionRecord> records) {
        var snapshot = org.mydotey.ai.artemis.proto.management.v1.EjectionRulesSnapshot.newBuilder();
        for (EjectionRecord record : records) {
            snapshot.addRulesBuilder()
                    .setService(toProto(record.instance().service()))
                    .setInstanceId(record.instance().instanceId())
                    .setReason(record.reason() == null ? "" : record.reason());
        }
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setSnapshot(snapshot)
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toEjectedEvent(EjectionRecord record) {
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setEjected(org.mydotey.ai.artemis.proto.management.v1.EjectionRule.newBuilder()
                        .setService(toProto(record.instance().service()))
                        .setInstanceId(record.instance().instanceId())
                        .setReason(record.reason() == null ? "" : record.reason())
                        .build())
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toRecoveredEvent(InstanceKey instance) {
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setRecovered(org.mydotey.ai.artemis.proto.management.v1.EjectionRecovered.newBuilder()
                        .setService(toProto(instance.service()))
                        .setInstanceId(instance.instanceId())
                        .build())
                .build();
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

    public SelfProtectionStatus toDomain(
            org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponseData data) {
        List<ServiceProtectionStatus> services =
                data.getServicesList().stream().map(this::toDomain).toList();
        var node = data.getNode();
        return new SelfProtectionStatus(
                data.getTrackedServices(),
                data.getProtectingServices(),
                new SelfProtectionStats(node.getWindowRenewals(), node.getMaxCount(), node.getProtecting()),
                services,
                data.getTruncated());
    }

    private ServiceProtectionStatus toDomain(
            org.mydotey.ai.artemis.proto.management.v1.ServiceSelfProtectionStatus status) {
        return new ServiceProtectionStatus(
                toDomain(status.getService()),
                status.getWindowRenewals(),
                status.getMaxCount(),
                status.getNodeFallback(),
                status.getProtecting(),
                status.getProtectingForMs());
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
