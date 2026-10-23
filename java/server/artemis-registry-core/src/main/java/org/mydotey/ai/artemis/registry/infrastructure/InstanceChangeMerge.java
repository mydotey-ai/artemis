package org.mydotey.ai.artemis.registry.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;

/**
 * 批量组批的合并规则（复制扇出与投影分发共用）：同 (service, instance) 后到覆盖先到
 * ——产生序内后到即该实例终态；保持首次出现位置，供调用方按序聚合/拆批。
 */
final class InstanceChangeMerge {

    private InstanceChangeMerge() {}

    static LinkedHashMap<InstanceKey, InstanceChange> latestPerInstance(List<InstanceChange> changes) {
        LinkedHashMap<InstanceKey, InstanceChange> merged = new LinkedHashMap<>();
        for (InstanceChange change : changes) {
            merged.put(new InstanceKey(change.key(), change.instance().instanceId()), change);
        }
        return merged;
    }
}
