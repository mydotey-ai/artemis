package org.mydotey.ai.artemis.management.domain;

import org.mydotey.ai.artemis.common.governance.EjectionScope;

/**
 * 摘除目标：INSTANCE 级由 (namespace, service, instanceId) 定位；
 * SERVER/ZONE/GROUP 级由 value（实例 metadata 值）定位，全局生效。
 */
public record EjectionTarget(EjectionScope scope, ServiceKey service, String instanceId, String value) {

    public static EjectionTarget instance(ServiceKey service, String instanceId) {
        return new EjectionTarget(EjectionScope.INSTANCE, service, instanceId, null);
    }

    public static EjectionTarget selector(EjectionScope scope, String value) {
        return new EjectionTarget(scope, null, null, value);
    }

    public EjectionTarget {
        if (scope == EjectionScope.INSTANCE) {
            require(service != null, "service required for INSTANCE scope");
            require(instanceId != null && !instanceId.isBlank(), "instanceId required for INSTANCE scope");
            require(value == null, "value not allowed for INSTANCE scope");
        } else {
            require(value != null && !value.isBlank(), "value required for " + scope + " scope");
            require(service == null && instanceId == null, "service/instanceId not allowed for " + scope + " scope");
        }
    }

    /** 稳定串表示（审计 target、日志）。 */
    public String describe() {
        return scope == EjectionScope.INSTANCE
                ? "instance:" + service.namespace() + "/" + service.service() + "/" + instanceId
                : scope.name().toLowerCase() + ":" + value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
