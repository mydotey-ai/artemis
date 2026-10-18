package org.mydotey.ai.artemis.registry.application;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ConfigProperty;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.DeregisterResponse;
import org.mydotey.ai.artemis.registry.domain.DeregisterResponseData;
import org.mydotey.ai.artemis.registry.domain.ErrorInfo;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponse;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponseData;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceError;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.LeaseDetail;
import org.mydotey.ai.artemis.registry.domain.LeaseStatus;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.RegisterResponseData;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstanceErrors;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.ServiceSummary;
import org.mydotey.ai.artemis.registry.domain.UpdateRequest;
import org.mydotey.ai.artemis.registry.domain.UpdateResponse;
import org.mydotey.ai.artemis.registry.domain.UpdateResponseData;

/**
 * dto（proto 生成消息）↔ 领域模型转换：同名类按包转换（proto.* ↔ domain.*）。
 *
 * <p>分工（tech-stack §4.6）：toDomain 外层请求由 MapStruct 编译期生成（字段级主映射）；
 * 枚举整表手写（proto 常量带前缀与领域名不对应）、嵌套结构/缺省值语义与 toProto 方向
 * （proto builder 无 setter/公开构造器，生成器不支持）以显式手写方法补齐。
 */
@Mapper
public interface RegistryProtoMapper {

    RegistryProtoMapper INSTANCE = Mappers.getMapper(RegistryProtoMapper.class);

    // ---- toDomain（请求，MapStruct 生成）----

    @Mapping(target = "services", source = "servicesList")
    RegisterRequest toDomain(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest request);

    @Mapping(target = "instances", source = "instancesList")
    DeregisterRequest toDomain(org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest request);

    @Mapping(target = "services", source = "servicesList")
    UpdateRequest toDomain(org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest request);

    @Mapping(target = "services", source = "servicesList")
    HeartbeatRequest toDomain(org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest request);

    // ---- toDomain（嵌套与枚举，手写：语义性转换）----

    default ServiceKey toDomain(org.mydotey.ai.artemis.proto.common.v1.ServiceKey key) {
        if (key == null) {
            return null;
        }
        return new ServiceKey(key.getNamespace(), key.getService());
    }

    default InstanceKey toDomain(org.mydotey.ai.artemis.proto.common.v1.InstanceKey key) {
        if (key == null) {
            return null;
        }
        return new InstanceKey(toDomain(key.getService()), key.getInstanceId());
    }

    default Instance toDomain(org.mydotey.ai.artemis.proto.common.v1.Instance instance) {
        if (instance == null) {
            return null;
        }
        return new Instance(
                instance.getInstanceId(),
                instance.getHost(),
                instance.getPort(),
                toDomain(instance.getStatus()),
                instance.getWeight(),
                instance.getMetadataMap());
    }

    default ServiceInstances toDomain(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances services) {
        if (services == null) {
            return null;
        }
        List<Instance> instances =
                services.getInstancesList().stream().map(this::toDomain).toList();
        return new ServiceInstances(toDomain(services.getKey()), instances);
    }

    /** 枚举整表手写：proto 常量带前缀与领域名不对应，MapStruct 无法按名映射。 */
    default InstanceStatus toDomain(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus status) {
        return switch (status) {
            case INSTANCE_STATUS_UNSPECIFIED -> InstanceStatus.UNSPECIFIED;
            case INSTANCE_STATUS_UNKNOWN -> InstanceStatus.UNKNOWN;
            case INSTANCE_STATUS_UP -> InstanceStatus.UP;
            case INSTANCE_STATUS_DOWN -> InstanceStatus.DOWN;
            case UNRECOGNIZED -> InstanceStatus.UNKNOWN;
        };
    }

    // ---- toProto（响应，手写 newBuilder：proto 消息无公开构造器/setter，生成器不支持该方向）----

    default org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse toProto(RegisterResponse response) {
        return org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse.newBuilder()
                .setStatus(toProto(response.status()))
                .setData(toProto(response.data()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse toProto(DeregisterResponse response) {
        return org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse.newBuilder()
                .setStatus(toProto(response.status()))
                .setData(toProto(response.data()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse toProto(UpdateResponse response) {
        return org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse.newBuilder()
                .setStatus(toProto(response.status()))
                .setData(toProto(response.data()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse toProto(HeartbeatResponse response) {
        return org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse.newBuilder()
                .setStatus(toProto(response.status()))
                .setData(toProto(response.data()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ResponseStatus toProto(ResponseStatus status) {
        org.mydotey.ai.artemis.proto.common.v1.ResponseStatus.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ResponseStatus.newBuilder()
                        .setAck(toProto(status.ack()));
        if (status.error() != null) {
            builder.setError(toProto(status.error()));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ErrorInfo toProto(ErrorInfo error) {
        return org.mydotey.ai.artemis.proto.common.v1.ErrorInfo.newBuilder()
                .setCode(error.code())
                .setMessage(error.message())
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.Ack toProto(Ack ack) {
        return ack == Ack.FAIL
                ? org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_FAIL
                : org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_SUCCESS;
    }

    default org.mydotey.ai.artemis.proto.registry.v1.RegisterResponseData toProto(RegisterResponseData data) {
        org.mydotey.ai.artemis.proto.registry.v1.RegisterResponseData.Builder builder =
                org.mydotey.ai.artemis.proto.registry.v1.RegisterResponseData.newBuilder();
        for (ServiceInstanceErrors serviceErrors : data.services()) {
            builder.addServices(toProto(serviceErrors));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.UpdateResponseData toProto(UpdateResponseData data) {
        org.mydotey.ai.artemis.proto.registry.v1.UpdateResponseData.Builder builder =
                org.mydotey.ai.artemis.proto.registry.v1.UpdateResponseData.newBuilder();
        for (ServiceInstanceErrors serviceErrors : data.services()) {
            builder.addServices(toProto(serviceErrors));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ServiceInstanceErrors toProto(ServiceInstanceErrors errors) {
        org.mydotey.ai.artemis.proto.common.v1.ServiceInstanceErrors.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstanceErrors.newBuilder()
                        .setKey(toProto(errors.key()));
        for (InstanceError failed : errors.failed()) {
            builder.addFailed(toProto(failed));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.InstanceError toProto(InstanceError error) {
        return org.mydotey.ai.artemis.proto.common.v1.InstanceError.newBuilder()
                .setInstance(toProto(error.instance()))
                .setError(toProto(error.error()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.InstanceKey toProto(InstanceKey key) {
        return org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                .setService(toProto(key.service()))
                .setInstanceId(key.instanceId())
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ServiceKey toProto(ServiceKey key) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace(key.namespace())
                .setService(key.service())
                .build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponseData toProto(DeregisterResponseData data) {
        org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponseData.Builder builder =
                org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponseData.newBuilder();
        for (InstanceError error : data.instances()) {
            builder.addInstances(toProto(error));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponseData toProto(HeartbeatResponseData data) {
        return org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponseData.newBuilder()
                .setServerTimeMs(data.serverTimeMs())
                .setSeq(data.seq())
                .build();
    }

    // ---- toProto（快照编码，手写：domain → proto common.v1，phase-3 快照落盘方向）----

    default org.mydotey.ai.artemis.proto.common.v1.Instance toProto(Instance instance) {
        return org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                .setInstanceId(instance.instanceId())
                .setHost(instance.host())
                .setPort(instance.port())
                .setStatus(toProto(instance.status()))
                .setWeight(instance.weight())
                .putAllMetadata(instance.metadata())
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ServiceInstances toProto(ServiceInstances services) {
        org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                        .setKey(toProto(services.key()));
        for (Instance instance : services.instances()) {
            builder.addInstances(toProto(instance));
        }
        return builder.build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.InstanceStatus toProto(InstanceStatus status) {
        return switch (status) {
            case UNSPECIFIED -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNSPECIFIED;
            case UNKNOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNKNOWN;
            case UP -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP;
            case DOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_DOWN;
        };
    }

    // ---- toProto（内部只读查询响应：查询成功恒 SUCCESS，空集合不是错误）----

    default org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse toListServicesResponse(
            List<ServiceSummary> services) {
        org.mydotey.ai.artemis.proto.management.v1.ListServicesResponseData.Builder data =
                org.mydotey.ai.artemis.proto.management.v1.ListServicesResponseData.newBuilder();
        for (ServiceSummary service : services) {
            data.addServices(org.mydotey.ai.artemis.proto.management.v1.ServiceSummary.newBuilder()
                    .setKey(toProto(service.key()))
                    .setInstanceCount(service.instanceCount()));
        }
        return org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse.newBuilder()
                .setStatus(successStatus())
                .setData(data)
                .build();
    }

    default org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse toGetServiceInstancesResponse(
            ServiceInstances services) {
        org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponseData.Builder data =
                org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponseData.newBuilder();
        if (services != null) {
            for (Instance instance : services.instances()) {
                data.addInstances(toProto(instance));
            }
        }
        return org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse.newBuilder()
                .setStatus(successStatus())
                .setData(data)
                .build();
    }

    default org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponse toGetLeaseStatusResponse(
            LeaseStatus status) {
        org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponseData.Builder data =
                org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponseData.newBuilder()
                        .setProtection(org.mydotey.ai.artemis.proto.management.v1.SelfProtectionStats.newBuilder()
                                .setWindowRenewals(status.protection().windowRenewals())
                                .setMaxCount(status.protection().maxCount())
                                .setProtecting(status.protection().protecting()));
        for (LeaseDetail lease : status.leases()) {
            data.addLeases(org.mydotey.ai.artemis.proto.management.v1.LeaseDetail.newBuilder()
                    .setService(toProto(lease.service()))
                    .setInstanceId(lease.instanceId())
                    .setAgeMs(lease.ageMs())
                    .setRemainingTtlMs(lease.remainingTtlMs()));
        }
        return org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponse.newBuilder()
                .setStatus(successStatus())
                .setData(data.setTruncated(status.truncated()))
                .build();
    }

    default org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponse toGetConfigStatusResponse(
            List<ConfigProperty> properties) {
        org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponseData.Builder data =
                org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponseData.newBuilder();
        for (ConfigProperty property : properties) {
            data.addProperties(org.mydotey.ai.artemis.proto.management.v1.ConfigProperty.newBuilder()
                    .setKey(property.key())
                    .setValue(property.value())
                    .setSource(property.source())
                    .setIsStatic(property.isStatic())
                    .setDefaultValue(property.defaultValue())
                    .setDoc(property.doc()));
        }
        return org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponse.newBuilder()
                .setStatus(successStatus())
                .setData(data)
                .build();
    }

    default org.mydotey.ai.artemis.proto.common.v1.ResponseStatus successStatus() {
        return toProto(new ResponseStatus(Ack.SUCCESS, null));
    }

    default org.mydotey.ai.artemis.proto.common.v1.ResponseStatus invalidArgumentStatus(String message) {
        return toProto(new ResponseStatus(Ack.FAIL, new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, message)));
    }
}
