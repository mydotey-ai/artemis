package org.mydotey.ai.artemis.client.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.InstanceStatus;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.UpstreamState;
import org.mydotey.ai.artemis.proto.common.v1.InstanceKey;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest;

/** SDK 值类型 ↔ proto wire 消息的单点转换（api 面不暴露 proto 类型）。 */
final class ProtoMapper {

    private ProtoMapper() {}

    static org.mydotey.ai.artemis.proto.common.v1.ServiceKey toProto(ServiceId id) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace(id.getNamespace())
                .setService(id.getService())
                .build();
    }

    static org.mydotey.ai.artemis.proto.common.v1.Instance toProto(ServiceInstance instance) {
        org.mydotey.ai.artemis.proto.common.v1.Instance.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                        .setInstanceId(instance.getInstanceId())
                        .setHost(instance.getHost())
                        .setPort(instance.getPort())
                        .setStatus(toProto(instance.getStatus()))
                        .setWeight(instance.getWeight());
        for (Map.Entry<String, String> entry : instance.getMetadata().entrySet()) {
            builder.putMetadata(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static org.mydotey.ai.artemis.proto.common.v1.InstanceStatus toProto(InstanceStatus status) {
        switch (status) {
            case UNSPECIFIED:
                return org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNSPECIFIED;
            case UNKNOWN:
                return org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNKNOWN;
            case DOWN:
                return org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_DOWN;
            case UP:
            default:
                return org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP;
        }
    }

    /** 心跳全量上报：按 service 分组的本地实例集 + 进程内单调 seq。 */
    static HeartbeatRequest heartbeatRequest(Map<ServiceId, List<ServiceInstance>> services, long seq) {
        HeartbeatRequest.Builder builder = HeartbeatRequest.newBuilder().setSeq(seq);
        for (Map.Entry<ServiceId, List<ServiceInstance>> entry : services.entrySet()) {
            builder.addServices(serviceInstances(entry.getKey(), entry.getValue()));
        }
        return builder.build();
    }

    static RegisterRequest registerRequest(ServiceId service, ServiceInstance instance) {
        return RegisterRequest.newBuilder()
                .addServices(serviceInstances(service, Collections.singletonList(instance)))
                .build();
    }

    static UpdateRequest updateRequest(ServiceId service, ServiceInstance instance) {
        return UpdateRequest.newBuilder()
                .addServices(serviceInstances(service, Collections.singletonList(instance)))
                .build();
    }

    static DeregisterRequest deregisterRequest(List<InstanceId> instances) {
        DeregisterRequest.Builder builder = DeregisterRequest.newBuilder();
        for (InstanceId instance : instances) {
            builder.addInstances(InstanceKey.newBuilder()
                    .setService(toProto(instance.getService()))
                    .setInstanceId(instance.getInstanceId())
                    .build());
        }
        return builder.build();
    }

    static org.mydotey.ai.artemis.proto.common.v1.ServiceInstances serviceInstances(
            ServiceId service, List<ServiceInstance> instances) {
        org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                        .setKey(toProto(service));
        for (ServiceInstance instance : instances) {
            builder.addInstances(toProto(instance));
        }
        return builder.build();
    }

    // ---- 发现侧（v0.3 phase-8）：selector ↔ 三 request、订阅事件/查询响应 → SDK 值类型 ----

    static ServiceId fromProto(org.mydotey.ai.artemis.proto.common.v1.ServiceKey key) {
        return ServiceId.newBuilder()
                .namespace(key.getNamespace())
                .service(key.getService())
                .build();
    }

    /** proto Instance → SDK 值类型（NEW/UPDATE 全量数据路径；metadata 保留到达序）。 */
    static ServiceInstance fromProto(ServiceId service, org.mydotey.ai.artemis.proto.common.v1.Instance instance) {
        ServiceInstance.Builder builder = ServiceInstance.newBuilder()
                .service(service)
                .instanceId(instance.getInstanceId())
                .host(instance.getHost())
                .port(instance.getPort())
                .weight(instance.getWeight())
                .status(fromProto(instance.getStatus()));
        if (!instance.getMetadataMap().isEmpty()) {
            builder.metadata(new java.util.LinkedHashMap<String, String>(instance.getMetadataMap()));
        }
        return builder.build();
    }

    private static InstanceStatus fromProto(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus status) {
        // UNSPECIFIED/UNKNOWN 保留原值：checksum statusName 与服务端 canonical 规则一致的前提
        switch (status) {
            case INSTANCE_STATUS_UNKNOWN:
                return InstanceStatus.UNKNOWN;
            case INSTANCE_STATUS_UP:
                return InstanceStatus.UP;
            case INSTANCE_STATUS_DOWN:
                return InstanceStatus.DOWN;
            case INSTANCE_STATUS_UNSPECIFIED:
                return InstanceStatus.UNSPECIFIED;
            default: // UNRECOGNIZED：无法识别的 wire 值统一映射本地 UNKNOWN 哨兵
                return InstanceStatus.UNKNOWN;
        }
    }

    static org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest subscribeRequest(DiscoverySelector selector) {
        org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest.Builder builder =
                org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest.newBuilder();
        for (ServiceId service : selector.services()) {
            builder.addServices(toProto(service));
        }
        builder.putAllLabels(selector.labels());
        builder.addAllClusters(selector.clusters());
        return builder.build();
    }

    static org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest queryRequest(DiscoverySelector selector) {
        org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest.Builder builder =
                org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest.newBuilder();
        for (ServiceId service : selector.services()) {
            builder.addServices(toProto(service));
        }
        builder.putAllLabels(selector.labels());
        builder.addAllClusters(selector.clusters());
        return builder.build();
    }

    static org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest checksumRequest(DiscoverySelector selector) {
        org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest.Builder builder =
                org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest.newBuilder();
        for (ServiceId service : selector.services()) {
            builder.addServices(toProto(service));
        }
        builder.putAllLabels(selector.labels());
        builder.addAllClusters(selector.clusters());
        return builder.build();
    }

    /** 订阅事件（单 service 变更批）→ SDK 值类型；DELETE 只携带 instanceId（其余字段无意义）。 */
    static ServiceChange fromProto(org.mydotey.ai.artemis.proto.discovery.v1.ServiceChange change) {
        ServiceId key = fromProto(change.getKey());
        List<InstanceChange> changes = new java.util.ArrayList<>();
        for (org.mydotey.ai.artemis.proto.discovery.v1.InstanceChange item : change.getChangesList()) {
            InstanceChange.Type type = fromProto(item.getType());
            if (type == InstanceChange.Type.DELETE) {
                changes.add(InstanceChange.delete(key, item.getInstance().getInstanceId()));
            } else if (type == InstanceChange.Type.UNKNOWN) {
                // 前向兼容：未知类型不构造实例（可能只带 instance_id），由缓存忽略
                changes.add(InstanceChange.unknown(key, item.getInstance().getInstanceId()));
            } else {
                changes.add(InstanceChange.upsert(fromProto(key, item.getInstance()), type));
            }
        }
        return new ServiceChange(key, Collections.unmodifiableList(changes));
    }

    static InstanceChange.Type fromProto(org.mydotey.ai.artemis.proto.discovery.v1.ChangeType type) {
        switch (type) {
            case CHANGE_TYPE_NEW:
                return InstanceChange.Type.NEW;
            case CHANGE_TYPE_UPDATE:
                return InstanceChange.Type.UPDATE;
            case CHANGE_TYPE_DELETE:
                return InstanceChange.Type.DELETE;
            default: // UNSPECIFIED / UNKNOWN / 未识别
                return InstanceChange.Type.UNKNOWN;
        }
    }

    /** 查询响应的 cluster 条目 → 本地全量替换条目。 */
    static List<LocalDiscoveryCache.ClusterEntry> clusterEntries(
            org.mydotey.ai.artemis.proto.discovery.v1.QueryResponseData data) {
        List<LocalDiscoveryCache.ClusterEntry> entries = new java.util.ArrayList<>();
        for (org.mydotey.ai.artemis.proto.discovery.v1.ClusterServiceInstances cluster : data.getServicesList()) {
            ServiceId service = fromProto(cluster.getInstances().getKey());
            List<ServiceInstance> instances = new java.util.ArrayList<>();
            for (org.mydotey.ai.artemis.proto.common.v1.Instance instance :
                    cluster.getInstances().getInstancesList()) {
                instances.add(fromProto(service, instance));
            }
            entries.add(new LocalDiscoveryCache.ClusterEntry(cluster.getCluster(), service, instances));
        }
        return entries;
    }

    static UpstreamState fromProto(org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState state) {
        switch (state) {
            case UPSTREAM_SYNC_STATE_SYNCING:
                return UpstreamState.SYNCING;
            case UPSTREAM_SYNC_STATE_SYNCED:
                return UpstreamState.SYNCED;
            case UPSTREAM_SYNC_STATE_DISCONNECTED:
                return UpstreamState.DISCONNECTED;
            default: // UNSPECIFIED / UNKNOWN / 未识别
                return UpstreamState.UNKNOWN;
        }
    }
}
