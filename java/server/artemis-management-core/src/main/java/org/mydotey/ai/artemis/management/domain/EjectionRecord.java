package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;

/**
 * 摘除操作记录（操作记录即状态，架构 §5.2 继承语义）：下线 = 追加 EJECT（可叠加、
 * 各带原因）；恢复 = 追加 RECOVER（反向记录，不删历史）。生效判定与对消见
 * {@link EjectionRecords}。sequence 单调递增（DB 自增主键 / 内存计数器）。
 */
public record EjectionRecord(
        EjectionTarget target,
        EjectionAction action,
        String reason,
        String operator,
        Instant timestamp,
        long sequence) {}
