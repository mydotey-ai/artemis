package org.mydotey.ai.artemis.management.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.AuditLogStore;

/**
 * 审计内存实现（内存态，重启丢失；DB 态由 MyBatis 实现接管）。
 */
public final class InMemoryAuditLogStore implements AuditLogStore {

    private final List<AuditLog> logs = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public AuditLog append(AuditLog log) {
        AuditLog stored = new AuditLog(
                log.operation(),
                log.target(),
                log.operator(),
                log.result(),
                log.detail(),
                log.timestamp(),
                sequence.incrementAndGet());
        logs.add(stored);
        return stored;
    }

    @Override
    public List<AuditLog> find(String operation, int limit) {
        return logs.stream()
                .filter(log -> operation == null
                        || operation.isBlank()
                        || log.operation().equals(operation))
                .sorted(Comparator.comparingLong(AuditLog::sequence).reversed())
                .limit(limit)
                .toList();
    }
}
