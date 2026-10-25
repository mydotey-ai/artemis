package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 摘除记录端口：只存取，生效计算归 {@link EjectionRecords} 纯函数（内存/DB 两态共享语义）。
 */
public interface EjectionStore {

    /**
     * 追加记录（EJECT/RECOVER）并分配 sequence，返回落库后的完整记录
     * （内存态计数器分配；DB 态自增主键回填）。对消判定在应用层，
     * 见 {@link EjectionRecords#isActive}。
     */
    EjectionRecord append(EjectionRecord record);

    /** 全部记录，按 sequence 升序（操作时间线；DB 态 = SELECT ORDER BY id）。 */
    List<EjectionRecord> findAll();
}
