package org.mydotey.ai.artemis.client.internal;

import java.util.List;
import org.mydotey.ai.artemis.client.ServiceId;

/** 单 service 变更批（internal，apply 层模型）：同 key 下保持产生序。 */
final class ServiceChange {

    final ServiceId key;
    final List<InstanceChange> changes;

    ServiceChange(ServiceId key, List<InstanceChange> changes) {
        this.key = key;
        this.changes = changes;
    }
}
