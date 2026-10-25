package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LeaseDetail;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
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

    // ---- 摘除规则流（management.v1，toProto 出站事件，四级）----

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toSnapshotEvent(List<EjectionRecord> records) {
        var snapshot = org.mydotey.ai.artemis.proto.management.v1.EjectionRulesSnapshot.newBuilder();
        for (EjectionRecord record : records) {
            snapshot.addRules(toRule(record));
        }
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setSnapshot(snapshot)
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toEjectedEvent(EjectionRecord record) {
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setEjected(toRule(record))
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent toRecoveredEvent(EjectionTarget target) {
        var builder = org.mydotey.ai.artemis.proto.management.v1.EjectionRecovered.newBuilder()
                .setScope(toProto(target.scope()));
        if (target.scope() == EjectionScope.INSTANCE) {
            builder.setService(toProto(target.service())).setInstanceId(target.instanceId());
        } else {
            builder.setTargetValue(target.value() == null ? "" : target.value());
        }
        return org.mydotey.ai.artemis.proto.management.v1.EjectionRulesEvent.newBuilder()
                .setRecovered(builder)
                .build();
    }

    private org.mydotey.ai.artemis.proto.management.v1.EjectionRule toRule(EjectionRecord record) {
        var builder = org.mydotey.ai.artemis.proto.management.v1.EjectionRule.newBuilder()
                .setReason(record.reason() == null ? "" : record.reason())
                .setScope(toProto(record.target().scope()));
        if (record.target().scope() == EjectionScope.INSTANCE) {
            builder.setService(toProto(record.target().service()))
                    .setInstanceId(record.target().instanceId());
        } else {
            builder.setTargetValue(
                    record.target().value() == null ? "" : record.target().value());
        }
        return builder.build();
    }

    private static org.mydotey.ai.artemis.proto.management.v1.EjectionScope toProto(EjectionScope scope) {
        return switch (scope) {
            case SERVER -> org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_SERVER;
            case ZONE -> org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_ZONE;
            case GROUP -> org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_GROUP;
            default -> org.mydotey.ai.artemis.proto.management.v1.EjectionScope.EJECTION_SCOPE_INSTANCE;
        };
    }

    // ---- 逻辑实例流（management.v1，toProto 出站事件，phase-4）----

    public org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent toLogicalSnapshotEvent(
            List<LogicalInstance> instances) {
        var snapshot = org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesSnapshot.newBuilder();
        for (LogicalInstance instance : instances) {
            snapshot.addInstances(toProto(instance));
        }
        return org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent.newBuilder()
                .setSnapshot(snapshot)
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent toLogicalUpsertedEvent(
            LogicalInstance instance) {
        return org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent.newBuilder()
                .setUpserted(toProto(instance))
                .build();
    }

    public org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent toLogicalDeletedEvent(
            ServiceKey service, String instanceId) {
        return org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent.newBuilder()
                .setDeleted(org.mydotey.ai.artemis.proto.management.v1.LogicalInstanceKey.newBuilder()
                        .setService(toProto(service))
                        .setInstanceId(instanceId)
                        .build())
                .build();
    }

    private org.mydotey.ai.artemis.proto.management.v1.LogicalInstance toProto(LogicalInstance instance) {
        return org.mydotey.ai.artemis.proto.management.v1.LogicalInstance.newBuilder()
                .setService(toProto(instance.service()))
                .setInstance(toProto(instance.instance()))
                .build();
    }

    private org.mydotey.ai.artemis.proto.common.v1.Instance toProto(Instance instance) {
        return org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                .setInstanceId(instance.instanceId())
                .setHost(instance.host())
                .setPort(instance.port())
                .setStatus(toProto(instance.status()))
                .setWeight(instance.weight())
                .putAllMetadata(instance.metadata())
                .build();
    }

    private static org.mydotey.ai.artemis.proto.common.v1.InstanceStatus toProto(InstanceStatus status) {
        return switch (status) {
            case DOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_DOWN;
            case UNKNOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNKNOWN;
            default -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP;
        };
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
