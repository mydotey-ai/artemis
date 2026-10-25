package org.mydotey.ai.artemis.management.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.common.governance.EjectionScope;

/**
 * 摘除记录纯函数（内存/DB 两态共享语义，单测一处覆盖）：对消计数、有效集计算、
 * 四级级联命中判定。
 *
 * <p>生效判定（per target）：Σ(EJECT) − Σ(RECOVER) > 0 为摘除中。命中优先序
 * INSTANCE &gt; SERVER &gt; ZONE &gt; GROUP（标注取最深一级）。管理操作级频率，
 * 全记录遍历可忽略
 * （ponytail: 记录到十万级再按 target 建索引缓存 active 集）。
 */
public final class EjectionRecords {

    private static final Comparator<EjectionTarget> TARGET_ORDER = Comparator.comparing(EjectionTarget::scope)
            .thenComparing(
                    target -> target.service() == null ? "" : target.service().namespace())
            .thenComparing(
                    target -> target.service() == null ? "" : target.service().service())
            .thenComparing(target -> target.instanceId() == null ? "" : target.instanceId())
            .thenComparing(target -> target.value() == null ? "" : target.value());

    private EjectionRecords() {}

    /** 目标当前是否摘除中（ΣEJECT − ΣRECOVER &gt; 0）。 */
    public static boolean isActive(List<EjectionRecord> records, EjectionTarget target) {
        int balance = 0;
        for (EjectionRecord record : records) {
            if (record.target().equals(target)) {
                balance += record.action() == EjectionAction.EJECT ? 1 : -1;
            }
        }
        return balance > 0;
    }

    /**
     * 有效（生效中）目标代表集：per target 按 FIFO 对消（RECOVER 对消最早未对消 EJECT），
     * 计数差 &gt; 0 时保留一条代表记录（最早未对消 EJECT——叠加多条同目标只算一个生效目标），
     * 差 ≤ 0 无代表。确定性排序（target 序 + sequence 序）。规则流快照即此集。
     */
    public static List<EjectionRecord> activeOf(List<EjectionRecord> records) {
        Map<EjectionTarget, List<EjectionRecord>> byTarget = new HashMap<>();
        for (EjectionRecord record : records) {
            if (record.action() == EjectionAction.EJECT) {
                byTarget.computeIfAbsent(record.target(), ignored -> new ArrayList<>())
                        .add(record);
            }
        }
        for (EjectionRecord record : records) {
            if (record.action() != EjectionAction.RECOVER) {
                continue;
            }
            List<EjectionRecord> pending = byTarget.get(record.target());
            if (pending != null && !pending.isEmpty()) {
                pending.remove(0); // FIFO 对消最早未对消 EJECT
            }
        }
        List<EjectionRecord> active = new ArrayList<>();
        for (List<EjectionRecord> pending : byTarget.values()) {
            if (!pending.isEmpty()) {
                active.add(pending.get(0));
            }
        }
        active.sort(
                Comparator.comparing(EjectionRecord::target, TARGET_ORDER).thenComparingLong(EjectionRecord::sequence));
        return List.copyOf(active);
    }

    /** INSTANCE 级有效记录按 service 计数（服务列表 ejectedCount；selector 级不折算）。 */
    public static Map<ServiceKey, Long> activeInstanceCountByService(List<EjectionRecord> active) {
        Map<ServiceKey, Long> counts = new HashMap<>();
        for (EjectionRecord record : active) {
            if (record.target().scope() == EjectionScope.INSTANCE) {
                counts.merge(record.target().service(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** 四级级联命中：instance 级 (service, instanceId) 等值，selector 级按 metadata[scope.metadataKey()] 等值；未命中 null。 */
    public static EjectionRecord match(List<EjectionRecord> active, ServiceKey service, Instance instance) {
        EjectionRecord matched = null;
        for (EjectionRecord record : active) {
            if (matches(record, service, instance)) {
                if (matched == null
                        || record.target().scope().ordinal()
                                < matched.target().scope().ordinal()) {
                    matched = record; // 优先序 INSTANCE(0) > SERVER(1) > ZONE(2) > GROUP(3)
                }
            }
        }
        return matched;
    }

    private static boolean matches(EjectionRecord record, ServiceKey service, Instance instance) {
        EjectionTarget target = record.target();
        if (target.scope() == EjectionScope.INSTANCE) {
            return target.service().equals(service) && target.instanceId().equals(instance.instanceId());
        }
        return target.value().equals(instance.metadata().get(target.scope().metadataKey()));
    }
}
