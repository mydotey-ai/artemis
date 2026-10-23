package org.mydotey.ai.artemis.registry.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMemberStatus;
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
import org.mydotey.ai.artemis.registry.domain.NodeState;
import org.mydotey.ai.artemis.registry.domain.NodeStatus;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.RegisterResponseData;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.SelfProtectionStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstanceErrors;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.ServiceProtectionStatus;
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

    default org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponse
            toGetSelfProtectionStatusResponse(SelfProtectionStatus status) {
        org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponseData.Builder data =
                org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponseData.newBuilder()
                        .setTrackedServices(status.trackedServices())
                        .setProtectingServices(status.protectingServices())
                        .setTruncated(status.truncated())
                        .setNode(org.mydotey.ai.artemis.proto.management.v1.SelfProtectionStats.newBuilder()
                                .setWindowRenewals(status.node().windowRenewals())
                                .setMaxCount(status.node().maxCount())
                                .setProtecting(status.node().protecting()));
        for (ServiceProtectionStatus service : status.services()) {
            data.addServices(org.mydotey.ai.artemis.proto.management.v1.ServiceSelfProtectionStatus.newBuilder()
                    .setService(toProto(service.service()))
                    .setWindowRenewals(service.windowRenewals())
                    .setMaxCount(service.maxCount())
                    .setNodeFallback(service.nodeFallback())
                    .setProtecting(service.protecting())
                    .setProtectingForMs(service.protectingForMs()));
        }
        return org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponse.newBuilder()
                .setStatus(successStatus())
                .setData(data)
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

    // ---- replication.v1：节点状态自报（toProto 手写，理由同上）----

    /** 枚举整表手写：proto 常量带前缀与领域名不对应；UNRECOGNIZED → null（入站校验整批拒绝）。 */
    default org.mydotey.ai.artemis.proto.replication.v1.ChangeType toProto(
            org.mydotey.ai.artemis.registry.domain.ChangeType type) {
        return switch (type) {
            case NEW -> org.mydotey.ai.artemis.proto.replication.v1.ChangeType.CHANGE_TYPE_NEW;
            case UPDATE -> org.mydotey.ai.artemis.proto.replication.v1.ChangeType.CHANGE_TYPE_UPDATE;
            case DELETE -> org.mydotey.ai.artemis.proto.replication.v1.ChangeType.CHANGE_TYPE_DELETE;
            case RENEW -> org.mydotey.ai.artemis.proto.replication.v1.ChangeType.CHANGE_TYPE_RENEW;
            // EVICTED 为 registry 内部事件：复制扇出在发送侧过滤，不应到达映射
            case EVICTED -> throw new IllegalArgumentException("EVICTED is not a replication change type");
        };
    }

    default org.mydotey.ai.artemis.registry.domain.ChangeType toDomain(
            org.mydotey.ai.artemis.proto.replication.v1.ChangeType type) {
        return switch (type) {
            case CHANGE_TYPE_NEW -> org.mydotey.ai.artemis.registry.domain.ChangeType.NEW;
            case CHANGE_TYPE_UPDATE -> org.mydotey.ai.artemis.registry.domain.ChangeType.UPDATE;
            case CHANGE_TYPE_DELETE -> org.mydotey.ai.artemis.registry.domain.ChangeType.DELETE;
            case CHANGE_TYPE_RENEW -> org.mydotey.ai.artemis.registry.domain.ChangeType.RENEW;
            default -> null;
        };
    }

    /** 入站批量：ServiceChange 按 key 展平；changeTimeMs 固定 0（源节点单调钟不可跨进程，D14）。 */
    default org.mydotey.ai.artemis.registry.domain.ReplicationBatch toDomain(
            org.mydotey.ai.artemis.proto.replication.v1.ReplicationBatch batch) {
        if (batch == null) {
            return null;
        }
        List<org.mydotey.ai.artemis.registry.domain.InstanceChange> changes = new ArrayList<>();
        for (org.mydotey.ai.artemis.proto.replication.v1.ServiceChange service : batch.getServicesList()) {
            for (org.mydotey.ai.artemis.proto.replication.v1.InstanceChange change : service.getChangesList()) {
                changes.add(new org.mydotey.ai.artemis.registry.domain.InstanceChange(
                        toDomain(service.getKey()), toDomain(change.getInstance()), toDomain(change.getType()), 0L));
            }
        }
        return new org.mydotey.ai.artemis.registry.domain.ReplicationBatch(batch.getSourceNodeId(), changes);
    }

    /** 出站批量：按 key 分组聚合（产生序保持），组 ReplicateRequest（扇出投递用）。 */
    default org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest toReplicateRequest(
            org.mydotey.ai.artemis.registry.domain.ReplicationBatch batch) {
        LinkedHashMap<
                        org.mydotey.ai.artemis.registry.domain.ServiceKey,
                        List<org.mydotey.ai.artemis.registry.domain.InstanceChange>>
                byService = new LinkedHashMap<>();
        for (org.mydotey.ai.artemis.registry.domain.InstanceChange change : batch.changes()) {
            byService.computeIfAbsent(change.key(), key -> new ArrayList<>()).add(change);
        }
        org.mydotey.ai.artemis.proto.replication.v1.ReplicationBatch.Builder batchBuilder =
                org.mydotey.ai.artemis.proto.replication.v1.ReplicationBatch.newBuilder()
                        .setSourceNodeId(batch.sourceNodeId());
        for (Map.Entry<
                        org.mydotey.ai.artemis.registry.domain.ServiceKey,
                        List<org.mydotey.ai.artemis.registry.domain.InstanceChange>>
                entry : byService.entrySet()) {
            org.mydotey.ai.artemis.proto.replication.v1.ServiceChange.Builder serviceBuilder =
                    org.mydotey.ai.artemis.proto.replication.v1.ServiceChange.newBuilder()
                            .setKey(toProto(entry.getKey()));
            for (org.mydotey.ai.artemis.registry.domain.InstanceChange change : entry.getValue()) {
                serviceBuilder.addChanges(org.mydotey.ai.artemis.proto.replication.v1.InstanceChange.newBuilder()
                        .setInstance(toProto(change.instance()))
                        .setType(toProto(change.type())));
            }
            batchBuilder.addServices(serviceBuilder);
        }
        return org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest.newBuilder()
                .setBatch(batchBuilder)
                .build();
    }

    default org.mydotey.ai.artemis.proto.replication.v1.NodeStatus toProto(NodeStatus status) {
        return org.mydotey.ai.artemis.proto.replication.v1.NodeStatus.newBuilder()
                .setNodeId(status.nodeId())
                .setState(toProto(status.state()))
                .setStartedAtMs(status.startedAtMs())
                .build();
    }

    default org.mydotey.ai.artemis.proto.replication.v1.ClusterMember toProto(ClusterMemberStatus status) {
        ClusterMember member = status.member();
        return org.mydotey.ai.artemis.proto.replication.v1.ClusterMember.newBuilder()
                .setNodeId(member.nodeId())
                .setPeerHost(wireHost(member.peer().host()))
                .setPeerPort(member.peer().port())
                .setExternalHost(wireHost(member.external().host()))
                .setExternalPort(member.external().port())
                .setState(toProto(status.state()))
                .build();
    }

    /** wire 上 host 必须是可直接拼接 host:port 的形式：IPv6 带方括号（domain 内不带）。 */
    default String wireHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** 枚举整表手写：proto 常量带前缀与领域名不对应。 */
    default org.mydotey.ai.artemis.proto.replication.v1.NodeState toProto(NodeState state) {
        return switch (state) {
            case UNKNOWN -> org.mydotey.ai.artemis.proto.replication.v1.NodeState.NODE_STATE_UNKNOWN;
            case STARTING -> org.mydotey.ai.artemis.proto.replication.v1.NodeState.NODE_STATE_STARTING;
            case UP -> org.mydotey.ai.artemis.proto.replication.v1.NodeState.NODE_STATE_UP;
            case DOWN -> org.mydotey.ai.artemis.proto.replication.v1.NodeState.NODE_STATE_DOWN;
        };
    }

    default NodeState toDomain(org.mydotey.ai.artemis.proto.replication.v1.NodeState state) {
        return switch (state) {
            case NODE_STATE_UNSPECIFIED, NODE_STATE_UNKNOWN, UNRECOGNIZED -> NodeState.UNKNOWN;
            case NODE_STATE_STARTING -> NodeState.STARTING;
            case NODE_STATE_UP -> NodeState.UP;
            case NODE_STATE_DOWN -> NodeState.DOWN;
        };
    }

    default NodeStatus toDomain(org.mydotey.ai.artemis.proto.replication.v1.NodeStatus status) {
        return new NodeStatus(status.getNodeId(), toDomain(status.getState()), status.getStartedAtMs());
    }

    default org.mydotey.ai.artemis.proto.common.v1.ResponseStatus successStatus() {
        return toProto(new ResponseStatus(Ack.SUCCESS, null));
    }

    default org.mydotey.ai.artemis.proto.common.v1.ResponseStatus invalidArgumentStatus(String message) {
        return toProto(new ResponseStatus(Ack.FAIL, new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, message)));
    }
}
