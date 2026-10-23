package org.mydotey.ai.artemis.discovery.application;

import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.discovery.domain.ChangeType;
import org.mydotey.ai.artemis.discovery.domain.ClusterServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.Instance;
import org.mydotey.ai.artemis.discovery.domain.InstanceChange;
import org.mydotey.ai.artemis.discovery.domain.InstanceStatus;
import org.mydotey.ai.artemis.discovery.domain.QueryRequest;
import org.mydotey.ai.artemis.discovery.domain.QueryResponse;
import org.mydotey.ai.artemis.discovery.domain.ServiceChange;
import org.mydotey.ai.artemis.discovery.domain.ServiceInstances;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.proto.common.v1.ErrorInfo;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponseData;
import org.mydotey.ai.artemis.proto.projection.v1.ChangeBatch;
import org.mydotey.ai.artemis.proto.projection.v1.WatchEvent;

/**
 * discovery/discovery.v1 与 projection.v1 契约的 dto ↔ 领域模型转换（手写：proto builder
 * 无公开构造器/setter，枚举常量带前缀且含 UNKNOWN 哨兵收敛）。
 */
public final class DiscoveryProtoMapper {

    public static final DiscoveryProtoMapper INSTANCE = new DiscoveryProtoMapper();

    private DiscoveryProtoMapper() {}

    // ---- 查询（discovery.v1，toDomain 请求 / toProto 响应）----

    public QueryRequest toDomain(org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest request) {
        List<ServiceKey> services = new ArrayList<>();
        for (org.mydotey.ai.artemis.proto.common.v1.ServiceKey key : request.getServicesList()) {
            services.add(toDomain(key));
        }
        return new QueryRequest(services, request.getLabelsMap());
    }

    public org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse toSuccessProto(QueryResponse response) {
        QueryResponseData.Builder data = QueryResponseData.newBuilder();
        for (ClusterServiceInstances service : response.services()) {
            data.addServicesBuilder().setCluster(service.cluster()).setInstances(toProto(service.instances()));
        }
        return org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse.newBuilder()
                .setStatus(ResponseStatus.newBuilder().setAck(org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_SUCCESS))
                .setData(data)
                .build();
    }

    public org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse toErrorProto(String code, String message) {
        return org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse.newBuilder()
                .setStatus(ResponseStatus.newBuilder()
                        .setAck(org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_FAIL)
                        .setError(ErrorInfo.newBuilder().setCode(code).setMessage(message)))
                .build();
    }

    // ---- 变更流（projection.v1，toDomain 入站）----

    /** 空批 = 订阅建立确认（phase-1 契约），由调用方判定。 */
    public List<ServiceChange> toDomain(ChangeBatch batch) {
        List<ServiceChange> services = new ArrayList<>(batch.getServicesCount());
        for (org.mydotey.ai.artemis.proto.projection.v1.ServiceChange service : batch.getServicesList()) {
            List<InstanceChange> changes = new ArrayList<>(service.getChangesCount());
            for (org.mydotey.ai.artemis.proto.projection.v1.InstanceChange change : service.getChangesList()) {
                changes.add(new InstanceChange(toDomain(change.getInstance()), toDomain(change.getType())));
            }
            services.add(new ServiceChange(toDomain(service.getKey()), changes));
        }
        return services;
    }

    public boolean isEstablishment(WatchEvent event) {
        return event.getChanges().getServicesList().isEmpty();
    }

    // ---- 公共模型（common.v1，双向）----

    public ServiceKey toDomain(org.mydotey.ai.artemis.proto.common.v1.ServiceKey key) {
        if (key == null) {
            return null;
        }
        return new ServiceKey(key.getNamespace(), key.getService());
    }

    public Instance toDomain(org.mydotey.ai.artemis.proto.common.v1.Instance instance) {
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

    public ServiceInstances toDomain(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances services) {
        if (services == null) {
            return null;
        }
        List<Instance> instances = new ArrayList<>(services.getInstancesCount());
        for (org.mydotey.ai.artemis.proto.common.v1.Instance instance : services.getInstancesList()) {
            instances.add(toDomain(instance));
        }
        return new ServiceInstances(toDomain(services.getKey()), instances);
    }

    /** 未知枚举收敛 UNKNOWN（与服务端版本解耦）。 */
    public ChangeType toDomain(org.mydotey.ai.artemis.proto.projection.v1.ChangeType type) {
        return switch (type) {
            case CHANGE_TYPE_NEW -> ChangeType.NEW;
            case CHANGE_TYPE_UPDATE -> ChangeType.UPDATE;
            case CHANGE_TYPE_DELETE -> ChangeType.DELETE;
            case CHANGE_TYPE_UNKNOWN, CHANGE_TYPE_UNSPECIFIED, UNRECOGNIZED -> ChangeType.UNKNOWN;
        };
    }

    public InstanceStatus toDomain(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus status) {
        return switch (status) {
            case INSTANCE_STATUS_UNSPECIFIED -> InstanceStatus.UNSPECIFIED;
            case INSTANCE_STATUS_UNKNOWN, UNRECOGNIZED -> InstanceStatus.UNKNOWN;
            case INSTANCE_STATUS_UP -> InstanceStatus.UP;
            case INSTANCE_STATUS_DOWN -> InstanceStatus.DOWN;
        };
    }

    public org.mydotey.ai.artemis.proto.common.v1.ServiceKey toProto(ServiceKey key) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace(key.namespace())
                .setService(key.service())
                .build();
    }

    public org.mydotey.ai.artemis.proto.common.v1.Instance toProto(Instance instance) {
        return org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                .setInstanceId(instance.instanceId())
                .setHost(instance.host())
                .setPort(instance.port())
                .setStatus(toProto(instance.status()))
                .setWeight(instance.weight())
                .putAllMetadata(instance.metadata())
                .build();
    }

    public org.mydotey.ai.artemis.proto.common.v1.ServiceInstances toProto(ServiceInstances services) {
        org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                        .setKey(toProto(services.key()));
        for (Instance instance : services.instances()) {
            builder.addInstances(toProto(instance));
        }
        return builder.build();
    }

    private org.mydotey.ai.artemis.proto.common.v1.InstanceStatus toProto(InstanceStatus status) {
        return switch (status) {
            case UNSPECIFIED -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNSPECIFIED;
            case UNKNOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNKNOWN;
            case UP -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP;
            case DOWN -> org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_DOWN;
        };
    }
}
