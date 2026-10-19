package org.mydotey.ai.artemis.client.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.InstanceStatus;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
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

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceInstances serviceInstances(
            ServiceId service, List<ServiceInstance> instances) {
        org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.Builder builder =
                org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                        .setKey(toProto(service));
        for (ServiceInstance instance : instances) {
            builder.addInstances(toProto(instance));
        }
        return builder.build();
    }
}
