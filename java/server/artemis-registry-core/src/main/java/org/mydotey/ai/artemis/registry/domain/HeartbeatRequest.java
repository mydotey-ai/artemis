package org.mydotey.ai.artemis.registry.domain;

import java.util.List;

/** 心跳请求：进程级全量实例集上报 + 客户端序号（不做集合 diff）。 */
public record HeartbeatRequest(List<ServiceInstances> services, long seq) {

    public HeartbeatRequest {
        services = List.copyOf(services);
    }
}
