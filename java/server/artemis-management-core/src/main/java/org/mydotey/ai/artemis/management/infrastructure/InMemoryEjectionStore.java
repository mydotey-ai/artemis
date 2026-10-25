package org.mydotey.ai.artemis.management.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionStore;

/**
 * 摘除记录内存实现（内存态，重启丢失；DB 态由 MyBatis 实现接管）。
 * 追加序即 sequence；读路径拷贝快照。
 *
 * <p>ponytail: 管理操作级频率，全量拷贝可忽略；记录到十万级改不可变快照 + volatile 发布。
 */
public final class InMemoryEjectionStore implements EjectionStore {

    private final List<EjectionRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public EjectionRecord append(EjectionRecord record) {
        EjectionRecord stored = new EjectionRecord(
                record.target(),
                record.action(),
                record.reason(),
                record.operator(),
                record.timestamp(),
                sequence.incrementAndGet());
        records.add(stored);
        return stored;
    }

    @Override
    public List<EjectionRecord> findAll() {
        return records.stream()
                .sorted(Comparator.comparingLong(EjectionRecord::sequence))
                .toList();
    }
}
