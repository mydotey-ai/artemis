package org.mydotey.ai.artemis.management.application;

import java.util.List;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 发布差异预览视图（phase-2）：基（currentWeight 列）= 最新发布快照的生效列
 * （baseReleaseId null；从未发布 = 空基，全量新增）或指定历史快照的生效列（回滚预览）；
 * next 列 = 当前编辑列。selector 等值对齐并集——只在基侧 = 将被移除（nextWeight
 * null），只在 next 侧 = 新增（currentWeight null）；结构单份直改，编辑删除的目标
 * 只能经快照基对照呈现。
 */
public record ReleaseDiff(ServiceKey service, String name, Long baseReleaseId, List<TargetDiff> targets) {

    /** 单目标差异；currentWeight/nextWeight null = 该侧无此目标。 */
    public record TargetDiff(java.util.Map<String, String> selector, Integer currentWeight, Integer nextWeight) {}

    public ReleaseDiff {
        targets = List.copyOf(targets);
    }
}
