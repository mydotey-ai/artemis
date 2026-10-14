package org.mydotey.ai.artemis.registry.domain;

import java.time.Duration;

/** 端口：租约 TTL 来源。清理时每轮读取（动态生效）；实现由基础设施/装配层注入。 */
@FunctionalInterface
public interface LeaseTtlSource {
    Duration leaseTtl();
}
