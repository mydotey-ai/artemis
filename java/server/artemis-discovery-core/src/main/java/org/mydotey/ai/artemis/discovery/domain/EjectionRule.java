package org.mydotey.ai.artemis.discovery.domain;

import org.mydotey.ai.artemis.common.governance.EjectionScope;

/**
 * 摘除规则（management 摘除记录的 discovery 侧投影，跨模块只走 wire）。
 * INSTANCE 级按 (namespace, service, instanceId) 匹配所有来源 cluster 的同键实例（D-E5）；
 * SERVER/ZONE/GROUP 级按实例 metadata[scope.metadataKey()] == value 匹配（全局生效）。
 */
public record EjectionRule(EjectionScope scope, ServiceKey service, String instanceId, String value, String reason) {

    public static EjectionRule instance(ServiceKey service, String instanceId, String reason) {
        return new EjectionRule(EjectionScope.INSTANCE, service, instanceId, null, reason);
    }

    public static EjectionRule selector(EjectionScope scope, String value, String reason) {
        return new EjectionRule(scope, null, null, value, reason);
    }
}
