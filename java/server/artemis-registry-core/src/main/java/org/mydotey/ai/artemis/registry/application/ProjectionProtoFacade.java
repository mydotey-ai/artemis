package org.mydotey.ai.artemis.registry.application;

import java.util.stream.Stream;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriber;
import org.mydotey.ai.artemis.registry.domain.ProjectionSubscriptions;

/**
 * 投影契约的 proto 门面：bootstrap 全量快照导出（server 侧按字节预算分片）与订阅注册/注销。
 * 全量快照与 peer bootstrap 同源（注册表共享快照，零克隆）。
 */
public final class ProjectionProtoFacade {

    private final InstanceRegistry registry;
    private final ProjectionSubscriptions subscriptions;

    public ProjectionProtoFacade(InstanceRegistry registry, ProjectionSubscriptions subscriptions) {
        this.registry = registry;
        this.subscriptions = subscriptions;
    }

    /**
     * 全量注册表快照的**惰性** proto 流（空表为空流）：server 接入层逐 service 分片发送，
     * 避免一次性物化整表 proto 副本。
     */
    public Stream<org.mydotey.ai.artemis.proto.common.v1.ServiceInstances> bootstrapSnapshotStream() {
        return registry.snapshots().stream().map(RegistryProtoMapper.INSTANCE::toProto);
    }

    public void subscribe(ProjectionSubscriber subscriber) {
        subscriptions.subscribe(subscriber);
    }

    public void unsubscribe(ProjectionSubscriber subscriber) {
        subscriptions.unsubscribe(subscriber);
    }
}
