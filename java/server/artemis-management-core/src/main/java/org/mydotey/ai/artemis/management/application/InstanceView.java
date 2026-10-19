package org.mydotey.ai.artemis.management.application;

import java.time.Instant;
import java.util.Map;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;

/**
 * 实例视图：registry 事实字段平铺 + 摘除标注（对应 HTTP 契约）。
 */
public record InstanceView(
        String instanceId,
        String host,
        int port,
        InstanceStatus status,
        int weight,
        Map<String, String> metadata,
        boolean ejected,
        String reason,
        Instant ejectedAt) {

    public InstanceView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static InstanceView of(Instance instance, EjectionRecord ejection) {
        return new InstanceView(
                instance.instanceId(),
                instance.host(),
                instance.port(),
                instance.status(),
                instance.weight(),
                instance.metadata(),
                ejection != null,
                ejection == null ? null : ejection.reason(),
                ejection == null ? null : ejection.ejectedAt());
    }
}
