package org.mydotey.ai.artemis.management.domain;

import java.time.Instant;

/**
 * 治理操作审计行（统一审计查询入口）：摘除/恢复为摘要行（完整留痕在摘除记录表，
 * 架构 §5.3 精化语义），其余治理操作（改密等）全量记录。
 */
public record AuditLog(
        String operation,
        String target,
        String operator,
        String result,
        String detail,
        Instant timestamp,
        long sequence) {}
