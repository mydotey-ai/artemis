package org.mydotey.ai.artemis.discovery.domain;

import org.mydotey.ai.artemis.common.governance.EjectionScope;

/**
 * 摘除目标（discovery 侧自持，与 management 侧同名异构——跨进程只走 wire）：
 * 恢复事件的定位键。INSTANCE 级 (service, instanceId)；selector 级 value。
 */
public record EjectionTarget(EjectionScope scope, ServiceKey service, String instanceId, String value) {

    public static EjectionTarget of(EjectionRule rule) {
        return new EjectionTarget(rule.scope(), rule.service(), rule.instanceId(), rule.value());
    }
}
