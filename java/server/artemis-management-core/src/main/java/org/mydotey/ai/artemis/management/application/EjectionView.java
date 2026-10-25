package org.mydotey.ai.artemis.management.application;

import java.time.Instant;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.EjectionAction;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;

/**
 * 摘除记录输出（扁平形式，对应 HTTP 契约）：四级目标 + 动作 + 审计字段全量平铺。
 */
public record EjectionView(
        EjectionScope scope,
        String namespace,
        String service,
        String instanceId,
        String value,
        EjectionAction action,
        String reason,
        String operator,
        Instant timestamp,
        long sequence) {

    public static EjectionView of(EjectionRecord record) {
        return new EjectionView(
                record.target().scope(),
                record.target().service() == null
                        ? null
                        : record.target().service().namespace(),
                record.target().service() == null
                        ? null
                        : record.target().service().service(),
                record.target().instanceId(),
                record.target().value(),
                record.action(),
                record.reason(),
                record.operator(),
                record.timestamp(),
                record.sequence());
    }
}
