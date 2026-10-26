package org.mydotey.ai.artemis.management.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;

/** 版本派生纯函数（phase-5）：翻转才推进版本，叠加/对消无翻转不推进。 */
class EjectionRecordsTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    void should_return_last_flip_sequence_when_replaying_records() {
        List<EjectionRecord> records = new ArrayList<>();
        records.add(eject("i1", 1)); // 翻转入 active → v1
        records.add(eject("i1", 2)); // 叠加：无翻转
        records.add(eject("i2", 3)); // 新目标翻转 → v3
        records.add(recover("i1", 4)); // i1 仍余一条 EJECT：无翻转
        records.add(recover("i1", 5)); // 完全对消翻转入 inactive → v5
        records.add(eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), 6)); // selector 翻转 → v6

        assertThat(EjectionRecords.lastFlipSequence(records)).isEqualTo(6);
    }

    @Test
    void should_return_zero_when_no_records() {
        assertThat(EjectionRecords.lastFlipSequence(List.of())).isZero();
    }

    @Test
    void should_not_advance_version_when_only_stacking_and_partial_cancel() {
        List<EjectionRecord> records = new ArrayList<>();
        records.add(eject("i1", 1)); // v1
        records.add(eject("i1", 2)); // 叠加
        records.add(recover("i1", 3)); // 部分对消（仍 active）：无翻转

        assertThat(EjectionRecords.lastFlipSequence(records)).isEqualTo(1);
    }

    private static EjectionRecord eject(String instanceId, long sequence) {
        return new EjectionRecord(
                EjectionTarget.instance(SERVICE, instanceId), EjectionAction.EJECT, "r", "admin", NOW, sequence);
    }

    private static EjectionRecord eject(EjectionTarget target, long sequence) {
        return new EjectionRecord(target, EjectionAction.EJECT, "r", "admin", NOW, sequence);
    }

    private static EjectionRecord recover(String instanceId, long sequence) {
        return new EjectionRecord(
                EjectionTarget.instance(SERVICE, instanceId), EjectionAction.RECOVER, null, "admin", NOW, sequence);
    }
}
