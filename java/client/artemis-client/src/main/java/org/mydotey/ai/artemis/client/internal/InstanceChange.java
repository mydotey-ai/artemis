package org.mydotey.ai.artemis.client.internal;

import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/**
 * 发现侧实例变更（internal，apply 层模型）：NEW/UPDATE 携带完整实例（upsert），
 * DELETE 只需 service + instanceId 定位（wire 契约其余字段无意义，不构造完整值类型）。
 */
final class InstanceChange {

    enum Type {
        NEW,
        UPDATE,
        DELETE,
        UNKNOWN
    }

    final ServiceId service;
    final String instanceId;
    final ServiceInstance instance; // NEW/UPDATE 非 null；DELETE null
    final Type type;

    private InstanceChange(ServiceId service, String instanceId, ServiceInstance instance, Type type) {
        this.service = service;
        this.instanceId = instanceId;
        this.instance = instance;
        this.type = type;
    }

    static InstanceChange upsert(ServiceInstance instance, Type type) {
        return new InstanceChange(instance.getService(), instance.getInstanceId(), instance, type);
    }

    static InstanceChange delete(ServiceId service, String instanceId) {
        return new InstanceChange(service, instanceId, null, Type.DELETE);
    }

    /** 无法识别的变更类型：不构造实例（前向兼容的新类型可能只带 instance_id），缓存直接忽略。 */
    static InstanceChange unknown(ServiceId service, String instanceId) {
        return new InstanceChange(service, instanceId, null, Type.UNKNOWN);
    }
}
