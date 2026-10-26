package org.mydotey.ai.artemis.management.infrastructure;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.mydotey.ai.artemis.management.domain.RouteRuleRelease;
import org.mydotey.ai.artemis.management.domain.RouteRuleReleaseStore;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 发布记录内存实现（内存态，重启丢失——与路由规则同清零；DB 态由 MyBatis 实现接管）。
 * append-only：id 计数器分配；并发 append 的 id 与列表顺序可能交错（list 排序收敛，
 * 管理操作级频率可忽略）。
 */
public final class InMemoryRouteRuleReleaseStore implements RouteRuleReleaseStore {

    private final AtomicLong ids = new AtomicLong();
    private final List<RouteRuleRelease> records = new CopyOnWriteArrayList<>();

    @Override
    public RouteRuleRelease append(RouteRuleRelease release) {
        RouteRuleRelease stored = new RouteRuleRelease(
                ids.incrementAndGet(),
                release.service(),
                release.name(),
                release.type(),
                release.snapshot(),
                release.rollbackTo(),
                release.operator(),
                release.releasedAt());
        records.add(stored);
        return stored;
    }

    @Override
    public RouteRuleRelease find(ServiceKey service, String name, long id) {
        return records.stream()
                .filter(record -> record.id() == id && matches(record, service, name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<RouteRuleRelease> list(ServiceKey service, String name, int limit) {
        return records.stream()
                .filter(record -> matches(record, service, name))
                .sorted(Comparator.comparingLong(RouteRuleRelease::id).reversed())
                .limit(limit)
                .toList();
    }

    private static boolean matches(RouteRuleRelease record, ServiceKey service, String name) {
        return record.service().equals(service) && record.name().equals(name);
    }
}
