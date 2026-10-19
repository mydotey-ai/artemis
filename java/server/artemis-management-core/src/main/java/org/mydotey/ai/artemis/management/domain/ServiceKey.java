package org.mydotey.ai.artemis.management.domain;

/**
 * 服务标识（namespace + service）。management 领域自有类型，跨进程只走 wire。
 */
public record ServiceKey(String namespace, String service) {}
