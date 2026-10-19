package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;

/**
 * 摘除记录：操作记录即状态。v0.1 单条/实例级/内存态；v0.4 扩展叠加记录、四级级联与持久化。
 */
public record EjectionRecord(InstanceKey instance, String reason, Instant ejectedAt) {}
