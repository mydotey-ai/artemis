package org.mydotey.ai.artemis.management.application;

import java.time.Instant;

/**
 * 摘除记录输出（扁平形式，对应 HTTP 契约）。
 */
public record EjectionView(String namespace, String service, String instanceId, String reason, Instant ejectedAt) {}
